/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.GeoFeature;
import io.github.tourgaze.entity.GeoRegion;
import io.github.tourgaze.parser.TrackParser;
import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GeoFeatureRepository;
import io.github.tourgaze.repository.GeoRegionRepository;
import io.github.tourgaze.store.StorageService;
import io.github.tourgaze.util.Geo;
import io.github.tourgaze.util.GeoHash;

/**
 * Auto-detects ride highlights — mountain passes crossed and named peaks
 * nearby — from cached OSM data. Coverage is lazy and per-region: the first
 * ride
 * in a geohash cell triggers one Overpass fetch for that cell, the results are
 * memoised in {@code geo_feature}, and the cell is marked done in
 * {@code geo_region} so it's never re-queried. After that every lookup is local
 * and offline. Honours TourGaze's local-first promise: one network call per new
 * region, never per view.
 */
@Service
public class PeakPassService {

	private static final Logger log = LoggerFactory.getLogger(PeakPassService.class);

	/** Geohash precision for coverage cells. 4 ≈ 39 km × 19 km. */
	private static final int REGION_PRECISION = 4;
	private static final double PASS_MATCH_M = 80; // track within this of a pass node = crossed
	private static final double PEAK_NEAR_M = 2000; // named peaks within this of the track
	private static final double PEAK_SUMMIT_M = 120; // track this close = actually summited
	private static final int MAX_CELLS_PER_RUN = 12; // politeness cap on Overpass fetches per import
	private static final long OVERPASS_GAP_MS = 1200; // pause between cell fetches

	private final GeoFeatureRepository featureRepo;
	private final GeoRegionRepository regionRepo;
	private final OverpassClient overpass;
	private final ActivityRepository activityRepo;
	private final StorageService storage;
	private final TrackParser trackParser;
	private final org.springframework.transaction.support.TransactionTemplate tx;

	public PeakPassService(GeoFeatureRepository featureRepo, GeoRegionRepository regionRepo,
			OverpassClient overpass, ActivityRepository activityRepo,
			StorageService storage, TrackParser trackParser,
			org.springframework.transaction.PlatformTransactionManager txManager) {
		this.featureRepo = featureRepo;
		this.regionRepo = regionRepo;
		this.overpass = overpass;
		this.activityRepo = activityRepo;
		this.storage = storage;
		this.trackParser = trackParser;
		this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
	}

	/** A matched highlight: the OSM feature plus where it sits on the ride. */
	public record Match(GeoFeature feature, double distM, int nearestIdx, double trackDistKm, boolean summited) {
	}

	public record Highlights(List<Match> passes, List<Match> peaks) {
	}

	// ── Lazy coverage ────────────────────────────────────────────────────────

	/** Fetch+cache any uncovered regions this ride touches. Async, best-effort. */
	@Async
	public void ensureRegionsForActivityAsync(String activityId) {
		try {
			ensureRegionsForActivity(activityId);
		} catch (Exception e) {
			log.info("[Highlights] region warm failed for {}: {}", activityId, e.toString());
		}
	}

	/**
	 * Deliberately NOT {@code @Transactional} at method level: the loop makes up
	 * to {@value #MAX_CELLS_PER_RUN} Overpass fetches with politeness pauses —
	 * ~a minute of wall clock that must not pin a DB connection from the async
	 * pool. Reads run in their own implicit transactions; each cell's results are
	 * persisted in a short per-cell transaction (a crash mid-run then loses at
	 * most the in-flight cell, which stays uncovered and is retried later).
	 */
	public void ensureRegionsForActivity(String activityId) {
		Activity a = activityRepo.findById(activityId).orElse(null);
		if (a == null)
			return;
		List<TrackPoint> pts = pointsOf(a);
		if (pts.isEmpty())
			return;

		Set<String> cells = cellsOf(pts);
		Set<String> known = new HashSet<>();
		regionRepo.findByGeocellIn(cells).forEach(r -> known.add(r.getGeocell()));

		int fetched = 0;
		for (String cell : cells) {
			if (known.contains(cell))
				continue;
			if (fetched >= MAX_CELLS_PER_RUN)
				break;
			double[] bb = GeoHash.decodeBbox(cell);
			List<GeoFeature> found = overpass.fetchBbox(bb[0], bb[1], bb[2], bb[3]);
			if (found == null) {
				// Transient Overpass failure (offline / rate-limited / timeout): leave
				// the cell UNcovered so a later view retries it. Caching it as "covered
				// with 0 features" would hide this region's highlights permanently.
				log.info(
						"[Highlights] Overpass unavailable for cell {} — leaving uncovered, will retry on a later view",
						cell);
				break; // be polite under rate-limiting: stop this run, resume next view
			}
			for (GeoFeature f : found)
				f.setGeocell(GeoHash.encode(f.getLat(), f.getLon(), REGION_PRECISION));
			// Keep only features that really fall in this cell (bbox edges round).
			found.removeIf(f -> !cell.equals(f.getGeocell()));
			tx.executeWithoutResult(status -> {
				if (!found.isEmpty())
					featureRepo.saveAll(found);
				regionRepo.save(new GeoRegion(cell, found.size()));
			});
			log.info("[Highlights] fetched {} features for cell {}", found.size(), cell);
			fetched++;
			if (fetched < MAX_CELLS_PER_RUN)
				sleep();
		}
	}

	// ── Matching ───────────────────────────────────────────────────────────

	@Transactional(readOnly = true)
	public Highlights computeHighlights(String activityId) {
		Activity a = activityRepo.findById(activityId).orElse(null);
		if (a == null)
			return new Highlights(List.of(), List.of());
		List<TrackPoint> pts = pointsOf(a);
		if (pts.isEmpty())
			return new Highlights(List.of(), List.of());

		List<GeoFeature> features = featureRepo.findByGeocellIn(cellsOf(pts));
		if (features.isEmpty())
			return new Highlights(List.of(), List.of());

		// Cumulative distance per point, so we can report where on the ride a
		// highlight sits (drives the elevation-chart marker).
		double[] cumKm = new double[pts.size()];
		for (int i = 1; i < pts.size(); i++) {
			cumKm[i] = cumKm[i - 1] + Geo.distanceM(pts.get(i - 1).lat(), pts.get(i - 1).lon(),
					pts.get(i).lat(), pts.get(i).lon()) / 1000.0;
		}
		// Subsample for the O(features × points) scan — ~50 m spacing is ample
		// for a 80 m / 2 km tolerance and keeps a long ride fast.
		int step = Math.max(1, pts.size() / 1500);

		List<Match> passes = new ArrayList<>(), peaks = new ArrayList<>();
		for (GeoFeature f : features) {
			double best = Double.MAX_VALUE;
			int bestIdx = 0;
			for (int i = 0; i < pts.size(); i += step) {
				double d = Geo.distanceM(f.getLat(), f.getLon(), pts.get(i).lat(), pts.get(i).lon());
				if (d < best) {
					best = d;
					bestIdx = i;
				}
			}
			boolean isPass = "PASS".equals(f.getType());
			double limit = isPass ? PASS_MATCH_M : PEAK_NEAR_M;
			if (best > limit)
				continue;
			Match m = new Match(f, best, bestIdx, cumKm[bestIdx], best <= PEAK_SUMMIT_M);
			(isPass ? passes : peaks).add(m);
		}
		passes.sort(Comparator.comparingDouble(Match::trackDistKm));
		peaks.sort(Comparator.comparingDouble(Match::distM));
		return new Highlights(passes, peaks);
	}

	// ── helpers ──────────────────────────────────────────────────────────────

	private Set<String> cellsOf(List<TrackPoint> pts) {
		Set<String> cells = new LinkedHashSet<>();
		for (TrackPoint p : pts)
			cells.add(GeoHash.encode(p.lat(), p.lon(), REGION_PRECISION));
		return cells;
	}

	private List<TrackPoint> pointsOf(Activity a) {
		try {
			return trackParser.parseByFilename(
					storage.readStoreBytes(a.getSourceFilename()), a.getSourceFilename()).points();
		} catch (Exception e) {
			log.debug("[Highlights] track read failed for {}: {}", a.getId(), e.getMessage());
			return List.of();
		}
	}

	private static void sleep() {
		try {
			Thread.sleep(OVERPASS_GAP_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
