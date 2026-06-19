/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.TrackPointDto;
import io.github.tourgaze.parser.ParseResult;
import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.store.StorageService;
import io.github.tourgaze.util.Geo;

/**
 * Lazily builds the on-disk JSON track cache for an activity.
 *
 * On first access:
 * 1. Read FIT file from store/{sourceFilename}
 * 2. Parse GPS track points via FitParser
 * 3. Serialize as JSON array → cache/{activityId}.json
 *
 * Subsequent accesses stream the cached file directly.
 */
@Service
public class TrackCacheService {

	private static final Logger log = LoggerFactory.getLogger(TrackCacheService.class);
	private static final int CHART_POINT_LIMIT = 800;

	private final StorageService storage;
	private final io.github.tourgaze.parser.TrackParser trackParser;
	private final ObjectMapper objectMapper;

	public TrackCacheService(StorageService storage, io.github.tourgaze.parser.TrackParser trackParser,
			ObjectMapper objectMapper) {
		this.storage = storage;
		this.trackParser = trackParser;
		this.objectMapper = objectMapper;
	}

	/** Returns full-resolution track cache path; lazily builds if missing. */
	public Path getOrBuild(String activityId, String sourceFilename) throws IOException {
		ensureCache(activityId, sourceFilename, false);
		return storage.cacheFile(activityId);
	}

	/** Returns chart-resolution track cache path; lazily builds if missing. */
	public Path getOrBuildChart(String activityId, String sourceFilename) throws IOException {
		ensureCache(activityId, sourceFilename, true);
		return storage.chartCacheFile(activityId);
	}

	/**
	 * Pre-warm BOTH caches in the background after a fresh import — so the
	 * first time the user opens the activity, the map endpoint streams the
	 * already-on-disk JSON instead of re-parsing the FIT. Pairs with the
	 * tile-warmer and async weather lookup in InboxService.
	 *
	 * Runs on the Spring async pool, so it can never block the import
	 * response. Errors are logged but never rethrown: a failed pre-warm just
	 * means the first viewer trip pays the parse cost — same as before this
	 * pre-cache existed.
	 */
	@Async
	public void prewarmAllAsync(String activityId, String sourceFilename) {
		try {
			ensureCache(activityId, sourceFilename, false);
			log.info("Pre-warmed track caches for activity {}", activityId);
		} catch (Exception e) {
			log.warn("Pre-warm failed for activity {} ({}): {}",
					activityId, sourceFilename, e.getMessage());
		}
	}

	private synchronized void ensureCache(String activityId, String sourceFilename, boolean chartOnly)
			throws IOException {
		Path fullCache = storage.cacheFile(activityId);
		Path chartCache = storage.chartCacheFile(activityId);

		boolean fullMissing = !Files.exists(fullCache);
		boolean chartMissing = !Files.exists(chartCache);

		if ((!chartOnly && !fullMissing) && !chartMissing)
			return;
		if (chartOnly && !chartMissing)
			return;

		Path fitPath = storage.storeFile(sourceFilename);
		byte[] data = Files.readAllBytes(fitPath);
		ParseResult result = trackParser.parseByFilename(data, sourceFilename);

		List<TrackPoint> raw = result.points();
		List<TrackPointDto> fullPoints = raw.stream()
				.map(p -> new TrackPointDto(
						Math.round(p.lat() * 1e6) / 1e6,
						Math.round(p.lon() * 1e6) / 1e6,
						p.altM() != null ? Math.round(p.altM() * 10.0) / 10.0 : null,
						p.hr(),
						p.speedMs() != null ? Math.round(p.speedMs() * 100.0) / 100.0 : null))
				.toList();
		List<TrackPointDto> chartPoints = buildChartPoints(raw, CHART_POINT_LIMIT);

		Files.createDirectories(fullCache.getParent());
		if (fullMissing && !chartOnly) {
			objectMapper.writeValue(fullCache.toFile(), fullPoints);
		}
		if (chartMissing) {
			objectMapper.writeValue(chartCache.toFile(), chartPoints);
		}
	}

	private List<TrackPointDto> buildChartPoints(List<TrackPoint> raw, int threshold) {
		if (raw.isEmpty())
			return List.of();

		int n = raw.size();
		double[] xs = new double[n];
		double[] ys = new double[n];

		double distKm = 0.0;
		for (int i = 0; i < n; i++) {
			if (i > 0) {
				TrackPoint a = raw.get(i - 1);
				TrackPoint b = raw.get(i);
				distKm += Geo.distanceKm(a.lat(), a.lon(), b.lat(), b.lon());
			}
			xs[i] = distKm;
			TrackPoint p = raw.get(i);
			ys[i] = p.altM() != null ? p.altM() : (p.speedMs() != null ? p.speedMs() : 0.0);
		}

		List<Integer> keep = lttb(xs, ys, threshold);
		List<TrackPointDto> out = new ArrayList<>(keep.size());
		for (int idx : keep) {
			TrackPoint p = raw.get(idx);
			out.add(new TrackPointDto(
					Math.round(p.lat() * 1e6) / 1e6,
					Math.round(p.lon() * 1e6) / 1e6,
					p.altM() != null ? Math.round(p.altM() * 10.0) / 10.0 : null,
					p.hr(),
					p.speedMs() != null ? Math.round(p.speedMs() * 100.0) / 100.0 : null,
					idx));
		}
		return out;
	}

	private List<Integer> lttb(double[] xs, double[] ys, int threshold) {
		int n = xs.length;
		if (n <= 2) {
			List<Integer> all = new ArrayList<>(n);
			for (int i = 0; i < n; i++)
				all.add(i);
			return all;
		}

		int t = Math.max(3, threshold);
		if (n <= t) {
			List<Integer> all = new ArrayList<>(n);
			for (int i = 0; i < n; i++)
				all.add(i);
			return all;
		}

		List<Integer> sampled = new ArrayList<>(t);
		sampled.add(0);
		int a = 0;

		double bucketSize = (double) (n - 2) / (double) (t - 2);
		for (int i = 0; i < t - 2; i++) {
			int avgStart = (int) Math.floor((i + 1) * bucketSize) + 1;
			int avgEnd = Math.min((int) Math.floor((i + 2) * bucketSize) + 1, n);

			double avgX = 0.0;
			double avgY = 0.0;
			int avgCount = Math.max(1, avgEnd - avgStart);
			if (avgEnd <= avgStart) {
				int j = Math.min(avgStart, n - 1);
				avgX = xs[j];
				avgY = ys[j];
			} else {
				for (int j = avgStart; j < avgEnd; j++) {
					avgX += xs[j];
					avgY += ys[j];
				}
				avgX /= avgCount;
				avgY /= avgCount;
			}

			int rangeStart = (int) Math.floor(i * bucketSize) + 1;
			int rangeEnd = Math.min((int) Math.floor((i + 1) * bucketSize) + 1, n - 1);
			if (rangeEnd < rangeStart)
				rangeEnd = rangeStart;

			double maxArea = -1.0;
			int maxIndex = rangeStart;
			double ax = xs[a];
			double ay = ys[a];
			for (int j = rangeStart; j <= rangeEnd; j++) {
				double area = Math.abs((ax - avgX) * (ys[j] - ay) - (ax - xs[j]) * (avgY - ay));
				if (area > maxArea) {
					maxArea = area;
					maxIndex = j;
				}
			}

			sampled.add(maxIndex);
			a = maxIndex;
		}

		sampled.add(n - 1);
		return sampled;
	}

	/** Evict the cache for an activity (e.g. on delete). */
	public void evict(String activityId) {
		try {
			Files.deleteIfExists(storage.cacheFile(activityId));
			Files.deleteIfExists(storage.chartCacheFile(activityId));
		} catch (IOException e) {
			// Non-fatal: leftover cache files just take up disk; user can
			// run the cache-purge admin action to clean up.
			log.warn("Cache evict for {} failed: {}", activityId, e.getMessage());
		}
	}
}
