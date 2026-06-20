/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.parser.TrackPoint;
import io.github.tourgaze.util.Geo;

/**
 * Geo-matches a ride's photos to its track and persists a {@code media.json}
 * manifest in the ride's media folder. Each photo is placed on the track by its
 * EXIF GPS if present, else by its EXIF timestamp matched to the nearest track
 * sample — so the replay can fade each photo in when the rider reaches its
 * point.
 */
@Service
public class MediaManifestService {

	private static final Logger log = LoggerFactory.getLogger(MediaManifestService.class);
	public static final String MANIFEST = "media.json";

	/**
	 * Filename prefix marking a discovered public-domain/CC photo, e.g.
	 * {@code img_public_Foo.jpg}.
	 */
	public static final String PUBLIC_PREFIX = "img_public_";
	/**
	 * Filename prefix marking a photo the user moved from public into their
	 * personal set.
	 */
	public static final String PERSONAL_PREFIX = "img_personal_";
	/** Legacy prefix from earlier discovered photos; still treated as public. */
	private static final String LEGACY_PUBLIC_PREFIX = "wiki_";

	private final ObjectMapper objectMapper;

	public MediaManifestService(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * {@code origin}: "public" for discovered Wikimedia Commons photos, else
	 * "private". {@code author}: who it's by — the rider's name for personal
	 * uploads, "Wikimedia Commons" for discovered photos.
	 */
	public record MediaItem(String name, Double lat, Double lon, Instant takenAt, Integer trackIndex, String origin,
			String author) {
	}

	/**
	 * Discovered Commons photos carry the {@link #PUBLIC_PREFIX} (or the legacy
	 * one) → public; all else private.
	 */
	public static boolean isPublic(String name) {
		return name.startsWith(PUBLIC_PREFIX) || name.startsWith(LEGACY_PUBLIC_PREFIX);
	}

	private static String originOf(String name) {
		return isPublic(name) ? "public" : "private";
	}

	/**
	 * Rename a photo's entry in {@code media.json} in place, preserving its
	 * geo-match (lat/lon/trackIndex/takenAt) and recomputing origin from the new
	 * name. Used when moving a public photo into the personal set — a full
	 * rebuild would lose the location, since discovered photos carry no EXIF GPS
	 * and the Commons coords are only known at discover time.
	 */
	public void renameInManifest(Path mediaDir, String oldName, String newName) {
		if (mediaDir == null)
			return;
		Path mf = mediaDir.resolve(MANIFEST);
		if (!Files.isRegularFile(mf))
			return;
		try {
			List<MediaItem> items = new ArrayList<>(List.of(objectMapper.readValue(mf.toFile(), MediaItem[].class)));
			for (int i = 0; i < items.size(); i++) {
				MediaItem it = items.get(i);
				if (it.name().equals(oldName)) {
					items.set(i, new MediaItem(newName, it.lat(), it.lon(), it.takenAt(), it.trackIndex(),
							originOf(newName), it.author()));
					break;
				}
			}
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(mf.toFile(), items);
		} catch (Exception e) {
			log.warn("[Media] manifest rename failed in {}: {}", mediaDir, e.getMessage());
		}
	}

	public void build(Path mediaDir, List<TrackPoint> points) {
		build(mediaDir, points, java.util.Map.of());
	}

	/**
	 * (Re)build {@code media.json} for every image in {@code mediaDir}, matched
	 * against {@code points}. {@code knownCoords} (filename → [lat, lon]) wins
	 * over EXIF — used for discovered Commons photos that carry their location in
	 * the API rather than in EXIF.
	 */
	public void build(Path mediaDir, List<TrackPoint> points, java.util.Map<String, double[]> knownCoords) {
		if (mediaDir == null || !Files.isDirectory(mediaDir))
			return;
		try {
			// Preserve any author already recorded — it's set at upload time and
			// can't be re-derived from the file, so a rebuild must not drop it.
			java.util.Map<String, String> authors = new java.util.HashMap<>();
			for (MediaItem it : read(mediaDir))
				if (it.author() != null)
					authors.put(it.name(), it.author());

			List<Path> images;
			try (var s = Files.list(mediaDir)) {
				images = s.filter(Files::isRegularFile)
						.filter(p -> !p.getFileName().toString().equals(MANIFEST))
						.sorted().toList();
			}
			List<MediaItem> items = new ArrayList<>();
			for (Path img : images)
				items.add(matchOne(img, points, knownCoords, authors));
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(mediaDir.resolve(MANIFEST).toFile(), items);
		} catch (Exception e) {
			log.warn("[Media] manifest build failed for {}: {}", mediaDir, e.getMessage());
		}
	}

	/** Set the author for one photo in the manifest (used at upload time). */
	public void setAuthor(Path mediaDir, String name, String author) {
		if (mediaDir == null || author == null)
			return;
		Path mf = mediaDir.resolve(MANIFEST);
		if (!Files.isRegularFile(mf))
			return;
		try {
			List<MediaItem> items = new ArrayList<>(List.of(objectMapper.readValue(mf.toFile(), MediaItem[].class)));
			for (int i = 0; i < items.size(); i++) {
				MediaItem it = items.get(i);
				if (it.name().equals(name)) {
					items.set(i, new MediaItem(it.name(), it.lat(), it.lon(), it.takenAt(), it.trackIndex(),
							it.origin(), author));
					break;
				}
			}
			objectMapper.writerWithDefaultPrettyPrinter().writeValue(mf.toFile(), items);
		} catch (Exception e) {
			log.debug("[Media] set author failed: {}", e.getMessage());
		}
	}

	/** Read the manifest; if absent, return bare name-only items (no geo). */
	public List<MediaItem> read(Path mediaDir) {
		if (mediaDir == null || !Files.isDirectory(mediaDir))
			return List.of();
		Path mf = mediaDir.resolve(MANIFEST);
		if (Files.isRegularFile(mf)) {
			try {
				return List.of(objectMapper.readValue(mf.toFile(), MediaItem[].class));
			} catch (Exception e) {
				log.debug("[Media] manifest read failed: {}", e.getMessage());
			}
		}
		try (var s = Files.list(mediaDir)) {
			return s.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(n -> !n.equals(MANIFEST))
					.sorted()
					.map(n -> new MediaItem(n, null, null, null, null, originOf(n), defaultAuthor(n, null)))
					.toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	/**
	 * Public photos are by "Wikimedia Commons" unless an author was already
	 * recorded.
	 */
	private static String defaultAuthor(String name, String existing) {
		if (existing != null)
			return existing;
		return isPublic(name) ? "Wikimedia Commons" : null;
	}

	private MediaItem matchOne(Path img, List<TrackPoint> points, java.util.Map<String, double[]> knownCoords,
			java.util.Map<String, String> authors) {
		Double lat = null, lon = null;
		Instant taken = null;
		String fname = img.getFileName().toString();
		String author = defaultAuthor(fname, authors.get(fname));
		double[] known = knownCoords.get(fname);
		if (known != null) {
			lat = known[0];
			lon = known[1];
			Integer idx = points.isEmpty() ? null : nearestByDistance(points, lat, lon);
			return new MediaItem(fname, lat, lon, null, idx, originOf(fname), author);
		}
		try {
			Metadata md = ImageMetadataReader.readMetadata(img.toFile());
			GpsDirectory gps = md.getFirstDirectoryOfType(GpsDirectory.class);
			if (gps != null) {
				GeoLocation loc = gps.getGeoLocation();
				if (loc != null && !loc.isZero()) {
					lat = loc.getLatitude();
					lon = loc.getLongitude();
				}
			}
			ExifSubIFDDirectory exif = md.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
			if (exif != null && exif.getDateOriginal() != null)
				taken = exif.getDateOriginal().toInstant();
		} catch (Exception e) {
			log.debug("[Media] EXIF read failed for {}: {}", img.getFileName(), e.getMessage());
		}

		Integer idx = null;
		if (lat != null && !points.isEmpty()) {
			idx = nearestByDistance(points, lat, lon);
		} else if (taken != null && !points.isEmpty()) {
			idx = nearestByTime(points, taken);
			// No GPS but matched by time → borrow the track point's position.
			if (idx != null) {
				lat = points.get(idx).lat();
				lon = points.get(idx).lon();
			}
		}
		return new MediaItem(fname, lat, lon, taken, idx, originOf(fname), author);
	}

	private int nearestByDistance(List<TrackPoint> pts, double lat, double lon) {
		int best = 0;
		double bestD = Double.MAX_VALUE;
		for (int i = 0; i < pts.size(); i++) {
			double d = Geo.distanceM(lat, lon, pts.get(i).lat(), pts.get(i).lon());
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	private Integer nearestByTime(List<TrackPoint> pts, Instant t) {
		int best = -1;
		long bestDiff = Long.MAX_VALUE;
		for (int i = 0; i < pts.size(); i++) {
			Instant pt = pts.get(i).time();
			if (pt == null)
				continue;
			long diff = Math.abs(pt.toEpochMilli() - t.toEpochMilli());
			if (diff < bestDiff) {
				bestDiff = diff;
				best = i;
			}
		}
		return best < 0 ? null : best;
	}
}
