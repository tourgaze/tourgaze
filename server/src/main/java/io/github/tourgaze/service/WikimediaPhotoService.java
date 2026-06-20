/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.parser.TrackParser;
import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.store.StorageService;
import io.github.tourgaze.util.Geo;

/**
 * "Find photos along the route" — queries Wikimedia Commons geosearch
 * (CC-licensed,
 * no API key) at points sampled along a ride's track, downloads the nearby
 * photos
 * into the ride's media folder, and geo-matches them so they show as map pins +
 * fade in during replay. Attribution (license + author) is preserved per photo.
 */
@Service
public class WikimediaPhotoService {

	private static final Logger log = LoggerFactory.getLogger(WikimediaPhotoService.class);
	private static final String API = "https://commons.wikimedia.org/w/api.php";
	private static final String UA = "TourGaze/1.0 (https://github.com/tourgaze/tourgaze)";
	private static final int SAMPLES = 10; // points along the route to search around
	private static final int RADIUS_M = 1500; // geosearch radius per sample point
	private static final int PER_POINT = 6;
	private static final int MAX_PHOTOS = 12;
	// Only keep photos this close to the track — "visible from the road". Anything
	// farther (a distant peak, a town off-route) is dropped, not shown.
	private static final double MAX_TRACK_DIST_M = 250.0;

	private final StorageService storage;
	private final TrackParser trackParser;
	private final MediaManifestService manifest;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();

	public WikimediaPhotoService(StorageService storage, TrackParser trackParser,
			MediaManifestService manifest, ObjectMapper json) {
		this.storage = storage;
		this.trackParser = trackParser;
		this.manifest = manifest;
		this.json = json;
	}

	public record DiscoveredPhoto(String name, double lat, double lon, String attribution) {
	}

	/** Discover + download Commons photos for the ride; returns what was saved. */
    public List<DiscoveredPhoto> discover(String sourceFilename) throws Exception {
        List<TrackPoint> points = trackParser.parseByFilename(
                Files.readAllBytes(storage.storeFile(sourceFilename)), sourceFilename).points();
        if (points.isEmpty()) return List.of();

        // De-dup candidates by Commons title across all sampled points.
        Map<String, JsonNode> candidates = new LinkedHashMap<>();
        int step = Math.max(1, points.size() / SAMPLES);
        for (int i = 0; i < points.size() && candidates.size() < MAX_PHOTOS * 2; i += step) {
            TrackPoint p = points.get(i);
            for (JsonNode page : geosearch(p.lat(), p.lon())) {
                String title = page.path("title").asText(null);
                if (title != null) candidates.putIfAbsent(title, page);
            }
        }

        // Rank candidates by distance to the track and drop the ones too far off
        // the road. Closest first → the most likely "visible from the road".
        record Cand(JsonNode page, double lat, double lon, double dist) {}
        List<Cand> ranked = new ArrayList<>();
        for (JsonNode page : candidates.values()) {
            if (page.path("imageinfo").path(0).path("thumburl").asText(null) == null) continue;
            JsonNode co = page.path("coordinates").path(0);
            if (!co.has("lat") || !co.has("lon")) continue;   // need a location to place it
            double lat = co.get("lat").asDouble(), lon = co.get("lon").asDouble();
            double d = distToTrack(points, lat, lon);
            if (d > MAX_TRACK_DIST_M) continue;                // too far off the road → skip
            ranked.add(new Cand(page, lat, lon, d));
        }
        ranked.sort(java.util.Comparator.comparingDouble(Cand::dist));

        Path dir = storage.activityMediaDir(sourceFilename);
        Files.createDirectories(dir);
        Map<String, double[]> knownCoords = new HashMap<>();
        List<DiscoveredPhoto> saved = new ArrayList<>();
        for (Cand c : ranked) {
            if (saved.size() >= MAX_PHOTOS) break;
            JsonNode ii = c.page().path("imageinfo").path(0);
            String name = fileNameFor(c.page().path("title").asText("photo"));
            // The user already moved this one into their personal set — don't
            // resurrect a public twin alongside it.
            String personalTwin = MediaManifestService.PERSONAL_PREFIX + name.substring(MediaManifestService.PUBLIC_PREFIX.length());
            if (Files.exists(dir.resolve(personalTwin))) continue;
            try {
                // Already cached from a previous search → keep it, don't re-download.
                // Still register its coords so the manifest rebuild re-matches it.
                if (Files.exists(dir.resolve(name))) {
                    knownCoords.put(name, new double[]{c.lat(), c.lon()});
                    saved.add(new DiscoveredPhoto(name, c.lat(), c.lon(), attribution(ii)));
                    continue;
                }
                byte[] bytes = download(ii.path("thumburl").asText());
                if (bytes.length == 0) continue;
                Files.write(dir.resolve(name), bytes);
                knownCoords.put(name, new double[]{c.lat(), c.lon()});
                saved.add(new DiscoveredPhoto(name, c.lat(), c.lon(), attribution(ii)));
            } catch (Exception e) {
                log.debug("[Commons] download failed: {}", e.getMessage());
            }
        }

        // Rebuild the manifest so the discovered photos get geo-matched track indices.
        manifest.build(dir, points, knownCoords);
        log.info("[Commons] discovered {} photos for {}", saved.size(), sourceFilename);
        return saved;
    }

	private List<JsonNode> geosearch(double lat, double lon) {
		String url = API + "?action=query&format=json&formatversion=2"
				+ "&generator=geosearch&ggsnamespace=6"
				+ "&ggscoord=" + lat + "%7C" + lon
				+ "&ggsradius=" + RADIUS_M + "&ggslimit=" + PER_POINT
				+ "&prop=imageinfo%7Ccoordinates&coprop=&colimit=1"
				+ "&iiprop=url%7Cextmetadata&iiurlwidth=1024";
		try {
			HttpResponse<String> r = http.send(
					HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
							.timeout(Duration.ofSeconds(15)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			if (r.statusCode() != 200)
				return List.of();
			JsonNode pages = json.readTree(r.body()).path("query").path("pages");
			List<JsonNode> out = new ArrayList<>();
			if (pages.isArray())
				pages.forEach(out::add);
			return out;
		} catch (Exception e) {
			log.debug("[Commons] geosearch failed at {},{}: {}", lat, lon, e.getMessage());
			return List.of();
		}
	}

	private byte[] download(String url) throws Exception {
		HttpResponse<byte[]> r = http.send(
				HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA)
						.timeout(Duration.ofSeconds(20)).GET().build(),
				HttpResponse.BodyHandlers.ofByteArray());
		return r.statusCode() == 200 ? r.body() : new byte[0];
	}

	private String attribution(JsonNode imageinfo) {
		JsonNode meta = imageinfo.path("extmetadata");
		String artist = stripHtml(meta.path("Artist").path("value").asText(""));
		String license = meta.path("LicenseShortName").path("value").asText("");
		String s = (artist + (license.isBlank() ? "" : " · " + license)).trim();
		return s.isBlank() ? "Wikimedia Commons" : s + " (Wikimedia Commons)";
	}

	private static String stripHtml(String s) {
		return s.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
	}

	/**
	 * Commons title → safe local filename, prefixed so discovered photos are
	 * distinguishable.
	 */
	private String fileNameFor(String title) {
		String base = title.replaceFirst("(?i)^File:", "").replaceAll("[^a-zA-Z0-9._-]", "_");
		if (!base.matches("(?i).*\\.(jpg|jpeg|png|webp|gif)$"))
			base += ".jpg";
		if (base.length() > 100)
			base = base.substring(base.length() - 100);
		return MediaManifestService.PUBLIC_PREFIX + base;
	}

	/**
	 * Smallest distance (metres) from a photo's coords to any point on the track.
	 */
	private static double distToTrack(List<TrackPoint> pts, double lat, double lon) {
		double min = Double.MAX_VALUE;
		for (TrackPoint p : pts) {
			double d = Geo.distanceM(lat, lon, p.lat(), p.lon());
			if (d < min)
				min = d;
		}
		return min;
	}
}
