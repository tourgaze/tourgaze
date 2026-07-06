/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.store.StorageService;

/**
 * Pre-warms the local tile cache after a FIT file is imported.
 * Computes the track bounding box and fetches OSM + terrain tiles
 * for zoom levels 10–14 in a background thread.
 */
@Service
public class TileWarmerService {

	private static final Logger log = LoggerFactory.getLogger(TileWarmerService.class);

	/** Tile providers and their upstream URL templates (same as TileController). */
	private static final java.util.Map<String, String> PROVIDER_URLS = java.util.Map.of(
			"osm", "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
			"terrain", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png");

	private static final int MIN_ZOOM = 10;
	private static final int MAX_ZOOM = 14;
	/** Hard ceiling of upstream fetches per warm run (~500 tiles ≈ 10 s paced). */
	private static final int MAX_TILES_PER_WARM = 500;
	private static final String[] SUBDOMAINS = { "a", "b", "c" };

	private final StorageService storage;
	private final HttpClient httpClient;

	public TileWarmerService(StorageService storage) {
		this.storage = storage;
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	/**
	 * Called after a FIT import — runs asynchronously so it never blocks the
	 * response.
	 */
	@Async
	public void warmAsync(List<TrackPoint> points) {
		if (points == null || points.isEmpty())
			return;

		double minLat = points.stream().mapToDouble(TrackPoint::lat).min().orElse(0);
		double maxLat = points.stream().mapToDouble(TrackPoint::lat).max().orElse(0);
		double minLon = points.stream().mapToDouble(TrackPoint::lon).min().orElse(0);
		double maxLon = points.stream().mapToDouble(TrackPoint::lon).max().orElse(0);

		// Add a small margin around the bounding box
		double margin = 0.05;
		minLat -= margin;
		maxLat += margin;
		minLon -= margin;
		maxLon += margin;

		log.info("Tile warming: bbox [{},{}] – [{},{}], zooms {}-{}",
				String.format("%.4f", minLat), String.format("%.4f", minLon),
				String.format("%.4f", maxLat), String.format("%.4f", maxLon), MIN_ZOOM, MAX_ZOOM);

		int fetched = 0;
		int skipped = 0;

		outer: for (String provider : PROVIDER_URLS.keySet()) {
			String urlTemplate = PROVIDER_URLS.get(provider);
			for (int z = MIN_ZOOM; z <= MAX_ZOOM; z++) {
				int xMin = lonToTile(minLon, z);
				int xMax = lonToTile(maxLon, z);
				int yMin = latToTile(maxLat, z); // note: lat is inverted for tile y
				int yMax = latToTile(minLat, z);

				for (int x = xMin; x <= xMax; x++) {
					for (int y = yMin; y <= yMax; y++) {
						// Bound the whole warm: a long ride's bbox at z14 alone is
						// thousands of tiles × 2 providers — an "Import all" backlog
						// would queue hours of upstream hammering on the async pool.
						// The corridor prefetch in the frontend covers the replay
						// path anyway; warming is best-effort, not exhaustive.
						if (fetched >= MAX_TILES_PER_WARM) {
							log.info("Tile warming stopped at cap ({} tiles) — bbox too large", MAX_TILES_PER_WARM);
							break outer;
						}
						Path cached = storage.tileFile(provider, z, x, y);
						if (Files.exists(cached)) {
							skipped++;
							continue;
						}
						try {
							fetch(urlTemplate, provider, z, x, y, cached);
							fetched++;
							Thread.sleep(20); // be polite to upstream servers
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							return;
						} catch (Exception e) {
							log.debug("Warm failed {}/{}/{}/{}: {}", provider, z, x, y, e.getMessage());
						}
					}
				}
			}
		}
		log.info("Tile warming complete: {} fetched, {} already cached", fetched, skipped);
	}

	private void fetch(String urlTemplate, String provider, int z, int x, int y, Path dest)
			throws IOException, InterruptedException {
		String sub = SUBDOMAINS[(x + y) % SUBDOMAINS.length];
		String url = urlTemplate
				.replace("{s}", sub)
				.replace("{z}", String.valueOf(z))
				.replace("{x}", String.valueOf(x))
				.replace("{y}", String.valueOf(y));

		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("User-Agent", "TourGaze/1.0 (tile warmer)")
				.timeout(Duration.ofSeconds(15))
				.build();

		HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
		if (resp.statusCode() == 200) {
			Files.createDirectories(dest.getParent());
			Files.write(dest, resp.body());
		}
	}

	// Slippy-map tile math
	private static int lonToTile(double lon, int z) {
		return (int) Math.floor((lon + 180.0) / 360.0 * (1 << z));
	}

	private static int latToTile(double lat, int z) {
		double rad = Math.toRadians(lat);
		return (int) Math.floor((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * (1 << z));
	}
}
