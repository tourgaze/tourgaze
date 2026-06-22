/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.tourgaze.dto.RouteCandidate;
import io.github.tourgaze.dto.SimilarRideDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.parser.TrackParser;
import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.store.StorageService;
import io.github.tourgaze.util.GeoHash;

/**
 * "Same route" detection for the ghost-chase compare picker. Each ride has a
 * route fingerprint — the set of ~150 m geohash cells it passes through —
 * computed at import and lazily backfilled here. Similarity between two rides
 * is
 * the Jaccard overlap of their cell sets; a shared tag also counts as a match.
 */
@Service
public class RouteSimilarityService {

	private static final Logger log = LoggerFactory.getLogger(RouteSimilarityService.class);
	private static final int PRECISION = 7; // ≈ 150 m cells
	private static final double GPS_THRESHOLD = 0.25;
	// A shared tag only counts if the rides ALSO overlap geographically by at least
	// this much — otherwise "both tagged holiday" would match a Mallorca ride to a
	// Sweden one. Lower than GPS_THRESHOLD so a tag still helps a
	// near-but-not-quite
	// route qualify, but never a far-away one.
	private static final double TAG_MIN_OVERLAP = 0.05;
	// Comparable length: keep only candidates whose distance is within 0.5×–2× of
	// the opened ride. A 30 km ride shouldn't list a 120 km one to "race".
	private static final double MIN_DIST_RATIO = 0.5;
	private static final double MAX_DIST_RATIO = 2.0;
	private static final int MAX_RESULTS = 12;

	private final ActivityRepository activityRepo;
	private final StorageService storage;
	private final TrackParser trackParser;

	public RouteSimilarityService(ActivityRepository activityRepo, StorageService storage, TrackParser trackParser) {
		this.activityRepo = activityRepo;
		this.storage = storage;
		this.trackParser = trackParser;
	}

	/** Space-separated set of geohash cells the track passes through. */
	public static String fingerprint(List<TrackPoint> points) {
		LinkedHashSet<String> cells = new LinkedHashSet<>();
		for (TrackPoint p : points)
			cells.add(GeoHash.encode(p.lat(), p.lon(), PRECISION));
		return String.join(" ", cells);
	}

	/** Cell set for a ride, computing + persisting the fingerprint if absent. */
	private Set<String> cellsOf(Activity a) {
		String fp = a.getRouteGeocells();
		if (fp == null || fp.isBlank()) {
			try {
				var pts = trackParser.parseByFilename(
						storage.readStoreBytes(a.getSourceFilename()), a.getSourceFilename()).points();
				fp = fingerprint(pts);
				a.setRouteGeocells(fp);
				activityRepo.save(a);
			} catch (Exception e) {
				log.debug("[Similar] fingerprint failed for {}: {}", a.getId(), e.getMessage());
				return Set.of();
			}
		}
		if (fp.isBlank())
			return Set.of();
		return new HashSet<>(Arrays.asList(fp.trim().split("\\s+")));
	}

	@Transactional
	public List<SimilarRideDto> findSimilar(String activityId) {
		Activity target = activityRepo.findById(activityId).orElse(null);
		if (target == null)
			return List.of();
		// The opened ride is the one place we still lazily backfill its
		// fingerprint (the heavy parse) if it's missing — candidates are read
		// from the cached column only, so /similar never parses N tracks.
		Set<String> tc = cellsOf(target);
		Set<String> targetTags = target.getTags().stream().map(Tag::getId).collect(Collectors.toSet());

		// One bulk query for tag membership instead of touching a.getTags() per
		// row; empty when the target is untagged (nothing can tag-match).
		Set<String> ridesSharingTag = targetTags.isEmpty()
				? Set.of()
				: activityRepo.findIdsSharingAnyTag(targetTags);

		Double targetDist = target.getDistanceKm();
		List<SimilarRideDto> out = new ArrayList<>();
		for (RouteCandidate a : activityRepo.findRouteCandidates(activityId)) {
			// Distance gate: only comparable-length rides (not too far apart).
			if (targetDist != null && targetDist > 0 && a.distanceKm() != null) {
				double ratio = a.distanceKm() / targetDist;
				if (ratio < MIN_DIST_RATIO || ratio > MAX_DIST_RATIO)
					continue;
			}

			double jac = 0;
			if (!tc.isEmpty()) {
				Set<String> ac = cellsOf(a.routeGeocells());
				if (!ac.isEmpty()) {
					Set<String> inter = new HashSet<>(tc);
					inter.retainAll(ac);
					int union = tc.size() + ac.size() - inter.size();
					jac = union == 0 ? 0 : (double) inter.size() / union;
				}
			}
			// A shared tag only counts when the routes also overlap on the ground —
			// no cross-location matches just because they share a label.
			boolean sharedTag = ridesSharingTag.contains(a.id()) && jac >= TAG_MIN_OVERLAP;

			if (jac >= GPS_THRESHOLD || sharedTag) {
				boolean gps = jac >= GPS_THRESHOLD;
				String type = gps && sharedTag ? "both" : (gps ? "gps" : "tag");
				double score = gps ? jac : 0.2; // tag-only gets a small base score
				out.add(new SimilarRideDto(
						a.id(), a.name(), a.activityType(), a.startTime(),
						a.distanceKm(), a.durationS(), a.startLocation(),
						Math.round(score * 1000) / 1000.0, type, a.riderName()));
			}
		}
		out.sort((x, y) -> Double.compare(y.score(), x.score()));
		return out.size() > MAX_RESULTS ? out.subList(0, MAX_RESULTS) : out;
	}

	/** Parse a cached fingerprint string into a cell set (empty if absent). */
	private static Set<String> cellsOf(String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank())
			return Set.of();
		return new HashSet<>(Arrays.asList(fingerprint.trim().split("\\s+")));
	}
}
