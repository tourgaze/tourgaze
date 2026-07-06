/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.entity.User;
import io.github.tourgaze.enums.ActivityType;
import io.github.tourgaze.event.ActivityEvents;
import io.github.tourgaze.parser.TrackParser;
import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.store.StorageService;

/**
 * Re-derives which bike a ride was done on, to repair the wrong gear that came
 * across in the MyTourbook import. Two passes:
 *
 * <ol>
 * <li><b>Cheap, offline</b> — from data already on the {@code activity} row:
 * <ul>
 * <li>FIT sub-sport {@code mountain} → <b>MTB</b> (device said it was a trail).
 * <li>fast (avg speed in the rider's own top band) → <b>racebike</b>.
 * <li>steep <i>and</i> slow → <i>ambiguous</i>: a forest MTB loop and a road
 * mountain pass both look like this. Hand to pass 2.
 * <li>anything else → no confident call → <b>no gear</b>.
 * </ul>
 * <li><b>OSM, only for the ambiguous few</b> — sample the GPS track and ask
 * OpenStreetMap how much of it runs through forest ({@link ForestClient}):
 * mostly-in-woods → <b>MTB</b>, otherwise → <b>racebike</b> (an open climb).
 * </ol>
 *
 * <p>
 * "Fast" and "slow" are <b>per rider</b>: the cut-offs are percentiles of
 * that user's own cycling speeds, not fixed km/h — a fast day for a tourer is a
 * slow day for a racer. With too few rides to build a distribution we fall back
 * to sensible absolutes.
 *
 * <p>
 * Dry-run by default: {@link #reclassify(boolean)} with {@code apply=false}
 * only reports what it would do; {@code apply=true} writes {@code gear_id}.
 */
@Service
public class GearPredictionService {

	private static final Logger log = LoggerFactory.getLogger(GearPredictionService.class);

	// ── Tunables ──────────────────────────────────────────────────────────────
	/** Avg speed at/above this percentile of the rider's rides = "fast". */
	private static final double FAST_PCTL = 0.65;
	/** Avg speed at/below this percentile of the rider's rides = "slow". */
	private static final double SLOW_PCTL = 0.40;
	/** Need at least this many rides to trust a per-rider distribution. */
	private static final int MIN_RIDES_FOR_PCTL = 6;
	/** Fallbacks (km/h) when a rider has too few rides for percentiles. */
	private static final double FALLBACK_FAST_KMH = 25.0;
	private static final double FALLBACK_SLOW_KMH = 18.0;
	/** Metres of climb per km at/above which a ride counts as "steep". */
	private static final double STEEP_M_PER_KM = 12.0;
	/** Track this much in the woods (0..1) → MTB rather than a road pass. */
	private static final double FOREST_FRAC = 0.40;
	/**
	 * Attribute key caching a ride's measured forest share (0..1). Written the
	 * first time OSM is consulted for a ride — a measurement, not a decision, so
	 * it is persisted even on dry runs. Later sweeps and the history profiles
	 * read it instead of re-asking Overpass, and it gives the profile matcher a
	 * third dimension: a ride mostly in the woods matches the bike your previous
	 * woods rides used.
	 */
	public static final String FOREST_FRAC_ATTR = "forestFrac";
	/** Track points sampled for the OSM forest lookup. */
	private static final int SAMPLE_POINTS = 25;
	/** Politeness pause between per-ride Overpass calls. */
	private static final long OVERPASS_GAP_MS = 1200;
	// ── History (speed+watt) profile matching ─────────────────────────────────
	/** A discipline needs this many confident rides before its profile is used. */
	private static final int PROFILE_MIN_RIDES = 3;
	/** Max normalised (z-score) distance to a profile to accept a match. */
	private static final double MATCH_MAX_Z = 1.0;
	/** The two profiles must differ by at least this in z-distance to pick one. */
	private static final double MATCH_MARGIN_Z = 0.4;

	/** Predicted discipline for a ride. */
	public enum Category {
		MTB, RACE, NONE, SKIP
	}

	private final ActivityRepository activityRepo;
	private final GearRepository gearRepo;
	private final ForestClient forest;
	private final StorageService storage;
	private final TrackParser trackParser;
	private final ApplicationEventPublisher events;
	private final org.springframework.transaction.PlatformTransactionManager txManager;

	public GearPredictionService(ActivityRepository activityRepo, GearRepository gearRepo,
			ForestClient forest, StorageService storage, TrackParser trackParser,
			ApplicationEventPublisher events,
			org.springframework.transaction.PlatformTransactionManager txManager) {
		this.activityRepo = activityRepo;
		this.gearRepo = gearRepo;
		this.forest = forest;
		this.storage = storage;
		this.trackParser = trackParser;
		this.events = events;
		this.txManager = txManager;
	}

	/**
	 * All sweep writes in one short transaction (null manager in unit tests →
	 * direct).
	 */
	private void runInTx(Runnable work) {
		if (txManager == null) {
			work.run();
			return;
		}
		new org.springframework.transaction.support.TransactionTemplate(txManager)
				.executeWithoutResult(status -> work.run());
	}

	/** One ride's before/after and why. */
	public record Prediction(String activityId, String name, Instant startTime,
			Double distanceKm, Double avgSpeedKmh, Integer avgPowerW, Double gradeMPerKm,
			String oldGear, Category category, String newGear,
			boolean changed, String reason) {
	}

	public record Report(int considered, int changed, boolean applied, List<Prediction> predictions) {
	}

	/** Mutable per-ride scratchpad carried across the classification stages. */
	private static final class Work {
		final Activity a;
		Category cat;
		String reason;
		/** True when the label is trustworthy enough to seed a speed/watt profile. */
		final boolean confident;
		/** True when a human set this gear by hand — ground truth, never touched. */
		final boolean locked;
		final Double avg;
		final Double grade;
		final Integer watt;
		/**
		 * Measured forest share (0..1) — cached attribute or fresh OSM; null = unknown.
		 */
		final Double forestFrac;

		Work(Activity a, Category cat, String reason, boolean confident, boolean locked,
				Double avg, Double grade, Integer watt, Double forestFrac) {
			this.a = a;
			this.cat = cat;
			this.reason = reason;
			this.confident = confident;
			this.locked = locked;
			this.avg = avg;
			this.grade = grade;
			this.watt = watt;
			this.forestFrac = forestFrac;
		}
	}

	public Report reclassify(boolean apply) {
		return reclassify(apply, false);
	}

	/**
	 * Deliberately NOT {@code @Transactional}: classification makes Overpass
	 * calls with politeness sleeps — minutes on a big sweep — and must not pin a
	 * DB connection/Hibernate session for that long. Rides are loaded with gear +
	 * user materialized (detached reads are then safe), and all writes happen in
	 * one short transaction at the end.
	 *
	 * @param deep
	 *            also measure the forest share of every ride that doesn't have a
	 *            cached one yet (not just the ambiguous steep+slow few). One
	 *            Overpass call per unmeasured ride, politely paced — slow on a
	 *            big library, but each result is cached on the ride, so it's a
	 *            one-time cost that then feeds the woods dimension of the
	 *            history profiles for good.
	 */
	public Report reclassify(boolean apply, boolean deep) {
		List<Activity> rides = activityRepo.findAllWithGearAndUser().stream()
				.filter(GearPredictionService::isBikeRide).toList();
		// Rides whose measured forest share must be persisted (even on dry runs —
		// it's a measurement, not a decision) and rides whose gear changes.
		List<Activity> fracMeasured = new ArrayList<>();

		// Per-rider "fast"/"slow" cut-offs from each rider's own speed spread.
		Map<String, double[]> cutoffsByUser = speedCutoffsByUser(rides);

		// ── Stage 1 — cheap heuristic (+ OSM for the ambiguous steep+slow few) ──
		List<Work> work = new ArrayList<>();
		boolean overpassHitThisRun = false;
		for (Activity a : rides) {
			Double avg = a.getAvgSpeedKmh();
			Double grade = gradeMPerKm(a);
			Double frac = storedForestFrac(a);
			if (frac == null && deep) {
				if (overpassHitThisRun)
					sleep();
				frac = forestLookup(a);
				overpassHitThisRun = true;
				if (frac != null) {
					a.getAttributes().put(FOREST_FRAC_ATTR, frac);
					fracMeasured.add(a);
				}
			}

			// Human-set gear is ground truth: lock it (never re-classify) and let it
			// train the profiles, labelled by the bike the human actually chose.
			if (isHumanSet(a)) {
				Category disc = disciplineOf(a.getGear());
				work.add(new Work(a, disc, "human-set gear — kept as-is", true, true,
						avg, grade, a.getAvgPowerW(), frac));
				continue;
			}

			double[] cut = cutoffsByUser.getOrDefault(userKey(a.getUser()),
					new double[] { FALLBACK_FAST_KMH, FALLBACK_SLOW_KMH });
			Cheap c = cheapClassify(avg, grade, a.getSubSport(), cut);

			Category cat = c.cat();
			String reason = c.reason();
			boolean confident = c.confident();
			if (c.steepSlow()) {
				// The ambiguous few: forest MTB loop vs open road pass — ask OSM
				// (or reuse the share measured on an earlier sweep).
				boolean cached = frac != null;
				if (!cached) {
					if (overpassHitThisRun)
						sleep();
					frac = forestLookup(a);
					overpassHitThisRun = true;
					if (frac != null) {
						a.getAttributes().put(FOREST_FRAC_ATTR, frac);
						fracMeasured.add(a);
					}
				}
				String src = cached ? "cached" : "OSM";
				if (frac == null) {
					cat = Category.SKIP;
					reason = "steep+slow, but OSM forest lookup unavailable — left unchanged";
					confident = false;
				} else if (frac >= FOREST_FRAC) {
					cat = Category.MTB;
					reason = String.format("steep+slow, %.0f%% of track in forest (%s)", frac * 100, src);
					confident = true;
				} else {
					cat = Category.RACE;
					reason = String.format("steep+slow, only %.0f%% in forest → road pass (%s)", frac * 100, src);
					confident = true;
				}
			}
			work.add(new Work(a, cat, reason, confident, false, avg, grade, a.getAvgPowerW(), frac));
		}

		// ── Stage 2 — learn each discipline's speed+watt+woods signature, from
		// the CONFIDENTLY-labelled rides only (never the wrong import). ──
		Profiles profiles = buildProfiles(work);

		// ── Stage 3 — rescue "no gear" rides that clearly match a learned
		// profile: history says which bike this most likely was. A ride mostly
		// in the woods pulls toward the bike your previous woods rides used. ──
		for (Work w : work) {
			if (w.locked || w.cat != Category.NONE)
				continue;
			Category match = profiles.classify(w.avg, w.watt, w.forestFrac);
			if (match != null) {
				w.cat = match;
				String dims = (w.forestFrac != null ? "speed/watt/woods" : "speed/watt");
				w.reason = "no strong terrain signal, but " + dims + " match your " + label(match) + " history";
			}
		}

		// ── Stage 4 — resolve to real gear rows, report, optionally apply. ──
		List<Gear> allGear = gearRepo.findAll(); // once — not per ride
		List<Prediction> out = new ArrayList<>();
		List<Activity> gearChanged = new ArrayList<>();
		int changed = 0;
		for (Work w : work) {
			Activity a = w.a;
			Gear target = w.cat == Category.MTB ? gearFor(Category.MTB, a.getUser(), allGear)
					: w.cat == Category.RACE ? gearFor(Category.RACE, a.getUser(), allGear)
							: null;
			String reason = w.reason;
			if ((w.cat == Category.MTB || w.cat == Category.RACE) && target == null)
				reason += " — but no matching bike in your gear list";

			String oldName = a.getGear() == null ? null : a.getGear().getName();
			String newName;
			boolean willChange;
			if (w.locked || w.cat == Category.SKIP) {
				newName = oldName; // human-set (locked) or OSM-unavailable — leave as-is
				willChange = false;
			} else if (w.cat == Category.NONE) {
				newName = null; // default: clear gear
				willChange = a.getGear() != null;
			} else if (target != null) {
				newName = target.getName();
				willChange = a.getGear() == null || !target.getId().equals(a.getGear().getId());
			} else {
				newName = oldName; // wanted a bike we don't have — don't wipe existing
				willChange = false;
			}

			if (apply && willChange) {
				a.setGear(w.cat == Category.NONE ? null : target);
				gearChanged.add(a);
			}
			if (willChange)
				changed++;

			out.add(new Prediction(a.getId(), a.getName(), a.getStartTime(), a.getDistanceKm(),
					w.avg, w.watt, w.grade, oldName, w.cat, newName, willChange, reason));
		}

		// One short write transaction for everything the sweep touched. Events go
		// inside it — the sidecar exporter is a @TransactionalEventListener
		// (AFTER_COMMIT) and would silently drop events published outside a tx.
		if (!fracMeasured.isEmpty() || !gearChanged.isEmpty()) {
			runInTx(() -> {
				java.util.LinkedHashSet<Activity> toSave = new java.util.LinkedHashSet<>(fracMeasured);
				toSave.addAll(gearChanged);
				for (Activity a : toSave)
					activityRepo.save(a);
				for (Activity a : gearChanged)
					events.publishEvent(new ActivityEvents.Changed(a.getId()));
			});
		}

		log.info("[GearPredict] considered {} rides, {} {} gear", rides.size(), changed,
				apply ? "changed" : "would change (dry run)");
		return new Report(rides.size(), changed, apply, out);
	}

	// ── Single-ride suggestion (import / inbox path) ──────────────────────────

	public record GearGuess(String gearId, String gearName) {
	}

	/**
	 * The sophisticated replacement for the old naive centroid proposer: suggest a
	 * bike for one ride from the cheap, offline signals — sub-sport, the library's
	 * own fast/slow bands, and a speed/watt profile learned from your trusted
	 * history. The expensive woods lookup is reserved for the explicit reclassify
	 * sweep, so importing stays snappy. Returns {@code null} when nothing is a
	 * confident match (the form is then left blank rather than guessing).
	 */
	/**
	 * Trained model for the single-ride path: global cut-offs + history profiles.
	 * Rebuilding it costs a full activity-table load, and the inbox warm sweep
	 * calls {@link #proposeGear} once per staged file — 50 dropped files used to
	 * mean 50 table scans. Cached; invalidated by activity change events and a
	 * short TTL as a safety net for paths that don't publish events.
	 */
	private record TrainedModel(double[] cut, Profiles profiles, long builtAt) {
	}

	private static final long MODEL_TTL_MS = 60_000;
	private volatile TrainedModel model;

	@org.springframework.context.event.EventListener({ ActivityEvents.Changed.class, ActivityEvents.Removed.class })
	void onActivityChanged(Object event) {
		model = null;
	}

	private TrainedModel trainedModel() {
		TrainedModel m = model;
		if (m != null && System.currentTimeMillis() - m.builtAt() < MODEL_TTL_MS)
			return m;
		List<Activity> rides = activityRepo.findAll().stream().filter(GearPredictionService::isBikeRide).toList();
		double[] cut = globalCutoffs(rides);
		m = new TrainedModel(cut, buildProfiles(historyLabels(rides, cut)), System.currentTimeMillis());
		model = m;
		return m;
	}

	@Transactional(readOnly = true)
	public GearGuess proposeGear(Double avgSpeedKmh, Double distanceKm, Double elevGainM,
			Integer avgPowerW, String subSport, ActivityType type) {
		if (avgSpeedKmh == null || distanceKm == null || distanceKm <= 0)
			return null;
		TrainedModel m = trainedModel();
		double[] cut = m.cut();
		Profiles profiles = m.profiles();

		Double grade = elevGainM == null ? null : elevGainM / distanceKm;
		Category cat = cheapClassify(avgSpeedKmh, grade, subSport, cut).cat();
		if (cat == Category.NONE) {
			// No forest share yet for an un-imported ride — the woods dimension
			// simply doesn't contribute (dist() skips missing dims).
			Category match = profiles.classify(avgSpeedKmh, avgPowerW, null);
			if (match != null)
				cat = match;
		}
		Gear g = (cat == Category.MTB || cat == Category.RACE) ? gearFor(cat, null, gearRepo.findAll()) : null;
		return g == null ? null : new GearGuess(g.getId(), g.getName());
	}

	/**
	 * Label history for profile training — cheap (no fresh OSM calls, but forest
	 * shares already measured by earlier sweeps are read back); trusts human-set
	 * gear.
	 */
	private List<Work> historyLabels(List<Activity> rides, double[] cut) {
		List<Work> hist = new ArrayList<>();
		for (Activity a : rides) {
			Double avg = a.getAvgSpeedKmh();
			Double grade = gradeMPerKm(a);
			Double frac = storedForestFrac(a);
			if (isHumanSet(a)) {
				hist.add(new Work(a, disciplineOf(a.getGear()), "", true, true, avg, grade, a.getAvgPowerW(), frac));
			} else {
				Cheap c = cheapClassify(avg, grade, a.getSubSport(), cut);
				hist.add(new Work(a, c.cat(), "", c.confident(), false, avg, grade, a.getAvgPowerW(), frac));
			}
		}
		return hist;
	}

	// ── Shared cheap (offline) classification ─────────────────────────────────

	/** Result of the offline signals; {@code steepSlow} defers to the OSM pass. */
	private record Cheap(Category cat, String reason, boolean confident, boolean steepSlow) {
	}

	private static Cheap cheapClassify(Double avg, Double grade, String subSport, double[] cut) {
		boolean fast = avg != null && avg >= cut[0];
		boolean slow = avg != null && avg <= cut[1];
		boolean steep = grade != null && grade >= STEEP_M_PER_KM;
		if ("mountain".equalsIgnoreCase(subSport))
			return new Cheap(Category.MTB, "FIT sub-sport = mountain", true, false);
		if (fast)
			return new Cheap(Category.RACE, String.format("fast (%.1f ≥ %.1f km/h)", avg, cut[0]), true, false);
		if (steep && slow)
			return new Cheap(Category.NONE, "steep+slow (ambiguous)", false, true);
		return new Cheap(Category.NONE, avg == null ? "no speed data" : "not fast, not steep+slow", false, false);
	}

	private static double[] globalCutoffs(List<Activity> rides) {
		List<Double> speeds = new ArrayList<>();
		for (Activity a : rides)
			if (a.getAvgSpeedKmh() != null)
				speeds.add(a.getAvgSpeedKmh());
		if (speeds.size() < MIN_RIDES_FOR_PCTL)
			return new double[] { FALLBACK_FAST_KMH, FALLBACK_SLOW_KMH };
		speeds.sort(Double::compareTo);
		return new double[] { percentile(speeds, FAST_PCTL), percentile(speeds, SLOW_PCTL) };
	}

	// ── Pass-2 helper: sample the track, ask OSM ──────────────────────────────

	/** Fraction of the track in forest, or {@code null} if track/OSM missing. */
	private Double forestLookup(Activity a) {
		List<TrackPoint> pts = pointsOf(a);
		if (pts.isEmpty())
			return null;
		return forest.forestFraction(sample(pts, SAMPLE_POINTS));
	}

	// ── History profiles: learn (speed, watt, woods) centroids per discipline ─

	/**
	 * A discipline's mean speed/watt/forest and how many confident rides built it.
	 */
	private record Centroid(double speed, boolean hasSpeed, double watt, boolean hasWatt,
			double forest, boolean hasForest, int n) {
	}

	/** MTB & RACE centroids plus the global spreads used to normalise each dim. */
	private record Profiles(Centroid mtb, Centroid race, double sdSpeed, double sdWatt, double sdForest) {

		/** Nearest matching discipline for a ride's signals, or null if unclear. */
		Category classify(Double avg, Integer watt, Double forestFrac) {
			double dM = dist(mtb, avg, watt, forestFrac);
			double dR = dist(race, avg, watt, forestFrac);
			boolean hasM = !Double.isNaN(dM), hasR = !Double.isNaN(dR);
			if (!hasM && !hasR)
				return null;
			Category best;
			double bestD, otherD;
			if (!hasR || (hasM && dM <= dR)) {
				best = Category.MTB;
				bestD = dM;
				otherD = dR;
			} else {
				best = Category.RACE;
				bestD = dR;
				otherD = dM;
			}
			if (bestD > MATCH_MAX_Z)
				return null; // not close to anything
			if (!Double.isNaN(otherD) && (otherD - bestD) < MATCH_MARGIN_Z)
				return null; // too close to call between the two bikes
			return best;
		}

		private double dist(Centroid c, Double avg, Integer watt, Double forestFrac) {
			if (c == null || c.n() < PROFILE_MIN_RIDES)
				return Double.NaN;
			double sum = 0;
			int dims = 0;
			if (avg != null && c.hasSpeed() && sdSpeed > 0) {
				double z = (avg - c.speed()) / sdSpeed;
				sum += z * z;
				dims++;
			}
			if (watt != null && c.hasWatt() && sdWatt > 0) {
				double z = (watt - c.watt()) / sdWatt;
				sum += z * z;
				dims++;
			}
			if (forestFrac != null && c.hasForest() && sdForest > 0) {
				double z = (forestFrac - c.forest()) / sdForest;
				sum += z * z;
				dims++;
			}
			return dims == 0 ? Double.NaN : Math.sqrt(sum / dims);
		}
	}

	private Profiles buildProfiles(List<Work> work) {
		List<Double> mtbS = new ArrayList<>(), mtbW = new ArrayList<>(), mtbF = new ArrayList<>();
		List<Double> raceS = new ArrayList<>(), raceW = new ArrayList<>(), raceF = new ArrayList<>();
		List<Double> allS = new ArrayList<>(), allW = new ArrayList<>(), allF = new ArrayList<>();
		for (Work w : work) {
			if (w.avg != null)
				allS.add(w.avg);
			if (w.watt != null)
				allW.add((double) w.watt);
			if (w.forestFrac != null)
				allF.add(w.forestFrac);
			if (!w.confident)
				continue;
			if (w.cat == Category.MTB) {
				if (w.avg != null)
					mtbS.add(w.avg);
				if (w.watt != null)
					mtbW.add((double) w.watt);
				if (w.forestFrac != null)
					mtbF.add(w.forestFrac);
			} else if (w.cat == Category.RACE) {
				if (w.avg != null)
					raceS.add(w.avg);
				if (w.watt != null)
					raceW.add((double) w.watt);
				if (w.forestFrac != null)
					raceF.add(w.forestFrac);
			}
		}
		return new Profiles(centroid(mtbS, mtbW, mtbF), centroid(raceS, raceW, raceF),
				sd(allS), sd(allW), sd(allF));
	}

	private static Centroid centroid(List<Double> speeds, List<Double> watts, List<Double> forests) {
		return new Centroid(mean(speeds), !speeds.isEmpty(), mean(watts), !watts.isEmpty(),
				mean(forests), !forests.isEmpty(),
				Math.max(speeds.size(), Math.max(watts.size(), forests.size())));
	}

	private static double mean(List<Double> xs) {
		if (xs.isEmpty())
			return 0;
		double s = 0;
		for (double x : xs)
			s += x;
		return s / xs.size();
	}

	private static double sd(List<Double> xs) {
		if (xs.size() < 2)
			return 0;
		double m = mean(xs), s = 0;
		for (double x : xs)
			s += (x - m) * (x - m);
		return Math.sqrt(s / (xs.size() - 1));
	}

	private static String label(Category c) {
		return c == Category.MTB ? "MTB" : "racebike";
	}

	private List<TrackPoint> pointsOf(Activity a) {
		try {
			return trackParser.parseByFilename(
					storage.readStoreBytes(a.getSourceFilename()), a.getSourceFilename()).points();
		} catch (Exception e) {
			log.debug("[GearPredict] track read failed for {}: {}", a.getId(), e.getMessage());
			return List.of();
		}
	}

	private static List<double[]> sample(List<TrackPoint> pts, int n) {
		List<double[]> out = new ArrayList<>(Math.min(n, pts.size()));
		int step = Math.max(1, pts.size() / n);
		for (int i = 0; i < pts.size(); i += step)
			out.add(new double[] { pts.get(i).lat(), pts.get(i).lon() });
		return out;
	}

	// ── Per-rider speed cut-offs ──────────────────────────────────────────────

	private Map<String, double[]> speedCutoffsByUser(List<Activity> rides) {
		Map<String, List<Double>> byUser = new HashMap<>();
		for (Activity a : rides) {
			if (a.getAvgSpeedKmh() == null)
				continue;
			byUser.computeIfAbsent(userKey(a.getUser()), k -> new ArrayList<>()).add(a.getAvgSpeedKmh());
		}
		Map<String, double[]> cutoffs = new HashMap<>();
		byUser.forEach((user, speeds) -> {
			if (speeds.size() < MIN_RIDES_FOR_PCTL) {
				cutoffs.put(user, new double[] { FALLBACK_FAST_KMH, FALLBACK_SLOW_KMH });
			} else {
				speeds.sort(Double::compareTo);
				cutoffs.put(user, new double[] { percentile(speeds, FAST_PCTL), percentile(speeds, SLOW_PCTL) });
			}
		});
		return cutoffs;
	}

	private static double percentile(List<Double> sorted, double p) {
		int idx = (int) Math.round(p * (sorted.size() - 1));
		return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
	}

	// ── Discipline → the rider's actual Gear row ──────────────────────────────

	/** The rider's bike for this discipline (own gear first, then any). */
	private Gear gearFor(Category cat, User user, List<Gear> allGear) {
		String uid = user == null ? null : user.getId();
		Gear own = null, any = null;
		for (Gear g : allGear) {
			if (g.getRetiredAt() != null || !matches(cat, g))
				continue;
			if (any == null)
				any = g;
			boolean sameUser = uid != null && g.getUser() != null && uid.equals(g.getUser().getId());
			if (sameUser && own == null)
				own = g;
		}
		return own != null ? own : any;
	}

	private static boolean matches(Category cat, Gear g) {
		String hay = ((g.getType() == null ? "" : g.getType()) + " "
				+ (g.getName() == null ? "" : g.getName()) + " "
				+ (g.getDescription() == null ? "" : g.getDescription())).toLowerCase();
		return switch (cat) {
			case MTB -> hay.contains("mtb") || hay.contains("mountain") || hay.contains("hibike");
			case RACE -> hay.contains("race") || hay.contains("road") || hay.contains("lapi");
			default -> false;
		};
	}

	// ── misc ──────────────────────────────────────────────────────────────────

	/** A human deliberately chose this ride's gear (see ActivityController). */
	private static boolean isHumanSet(Activity a) {
		return a.getGear() != null && "user".equals(a.getAttributes().get("gearSource"));
	}

	/** Forest share cached on the ride by an earlier sweep, or null. */
	private static Double storedForestFrac(Activity a) {
		Object v = a.getAttributes().get(FOREST_FRAC_ATTR);
		if (v instanceof Number n)
			return n.doubleValue();
		if (v instanceof String s) {
			try {
				return Double.parseDouble(s);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** Which discipline a gear row represents (for training on locked rides). */
	private static Category disciplineOf(Gear g) {
		if (g == null)
			return Category.NONE;
		if (matches(Category.MTB, g))
			return Category.MTB;
		if (matches(Category.RACE, g))
			return Category.RACE;
		return Category.NONE;
	}

	/** Only classify bike-shaped rides — never tag a run/hike with a bike. */
	private static boolean isBikeRide(Activity a) {
		String t = a.getActivityType() == null ? "" : a.getActivityType().trim().toLowerCase();
		return t.isEmpty() || t.startsWith("cycl") || t.equals("bike") || t.equals("ride");
	}

	private static Double gradeMPerKm(Activity a) {
		Double gain = a.getElevationGainM();
		Double dist = a.getDistanceKm();
		if (gain == null || dist == null || dist <= 0)
			return null;
		return gain / dist;
	}

	private static String userKey(User u) {
		return u == null ? " none" : u.getId();
	}

	private static void sleep() {
		try {
			Thread.sleep(OVERPASS_GAP_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
