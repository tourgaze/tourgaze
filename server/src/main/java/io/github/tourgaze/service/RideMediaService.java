/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.github.tourgaze.parser.TrackParser;
import io.github.tourgaze.store.StorageService;

/**
 * Add/remove photos on an already-imported ride (EditTour). Writes into the
 * ride's {@code store/<ride>_media/} folder and rebuilds the geo-matched
 * {@code media.json} manifest (EXIF GPS/time) so map pins + replay fades
 * update.
 */
@Service
public class RideMediaService {

	private final StorageService storage;
	private final TrackParser trackParser;
	private final MediaManifestService manifest;
	private final MediaProcessor mediaProcessor;
	private final io.github.tourgaze.repository.ActivityRepository activityRepo;

	public RideMediaService(StorageService storage, TrackParser trackParser,
			MediaManifestService manifest, MediaProcessor mediaProcessor,
			io.github.tourgaze.repository.ActivityRepository activityRepo) {
		this.storage = storage;
		this.trackParser = trackParser;
		this.manifest = manifest;
		this.mediaProcessor = mediaProcessor;
		this.activityRepo = activityRepo;
	}

	public List<String> save(String sourceFilename, MultipartFile[] files) throws IOException {
		Path dir = storage.activityMediaDir(sourceFilename);
		Files.createDirectories(dir);
		List<String> saved = new ArrayList<>();
		if (files != null) {
			for (MultipartFile f : files) {
				String orig = f.getOriginalFilename();
				if (orig == null || orig.isBlank())
					continue;
				String e = ext(orig);
				if (!mediaProcessor.isAccepted(e))
					continue; // images + videos
				String name = unique(dir, sanitize(orig));
				// Downscale oversized images (videos pass through untouched); encrypted
				// to <name>.enc when store encryption is on.
				storage.writeEncrypted(dir.resolve(name), mediaProcessor.process(f.getBytes(), e));
				saved.add(name);
			}
		}
		rebuild(sourceFilename);
		// Personal uploads are authored by the ride's rider.
		String rider = activityRepo.findRiderName(sourceFilename).orElse(null);
		if (rider != null) {
			for (String name : saved) {
				if (!MediaManifestService.isPublic(name))
					manifest.setAuthor(dir, name, rider);
			}
		}
		return saved;
	}

	public void delete(String sourceFilename, String name) throws IOException {
		if (name.contains("/") || name.contains("\\") || name.contains(".."))
			return;
		storage.deleteEncrypted(storage.activityMediaDir(sourceFilename).resolve(name));
		rebuild(sourceFilename);
	}

	/**
	 * Move a discovered public photo into the user's personal set: rename
	 * {@code img_public_*} → {@code img_personal_*} so it reads as private, and
	 * patch the manifest in place so its track position is preserved (a full
	 * rebuild can't re-derive the Commons coords). No-op for non-public photos.
	 * Returns the updated manifest.
	 */
	public List<MediaManifestService.MediaItem> makePersonal(String sourceFilename, String name) throws IOException {
		Path dir = storage.activityMediaDir(sourceFilename);
		if (name.contains("/") || name.contains("\\") || name.contains(".."))
			return manifest.read(dir);
		if (!MediaManifestService.isPublic(name) || !storage.encryptedExists(dir.resolve(name)))
			return manifest.read(dir);
		String stripped = name
				.replaceFirst("^" + MediaManifestService.PUBLIC_PREFIX, "")
				.replaceFirst("^wiki_", "");
		String newName = unique(dir, MediaManifestService.PERSONAL_PREFIX + stripped);
		storage.renameEncrypted(dir, name, newName);
		manifest.renameInManifest(dir, name, newName);
		return manifest.read(dir);
	}

	/** Re-geo-match every photo in the ride's media folder against its track. */
	public void rebuild(String sourceFilename) {
		try {
			var points = trackParser.parseByFilename(
					storage.readStoreBytes(sourceFilename), sourceFilename).points();
			manifest.build(storage.activityMediaDir(sourceFilename), points);
		} catch (Exception e) {
			// Manifest is best-effort; the photos themselves are already saved.
			manifest.build(storage.activityMediaDir(sourceFilename), List.of());
		}
	}

	private static String ext(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
	}

	private static String sanitize(String orig) {
		String n = orig.replaceAll("[^a-zA-Z0-9._-]", "_");
		// Uploads are "private"; only discovered Commons photos carry the public
		// prefix. Never let a user file masquerade as discovered.
		String lower = n.toLowerCase();
		if (lower.startsWith(MediaManifestService.PUBLIC_PREFIX) || lower.startsWith("wiki_"))
			n = "u_" + n;
		return n.length() > 120 ? n.substring(n.length() - 120) : n;
	}

	private String unique(Path dir, String name) {
		if (!storage.encryptedExists(dir.resolve(name)))
			return name;
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 2;; i++) {
			String c = base + "_" + i + ext;
			if (!storage.encryptedExists(dir.resolve(c)))
				return c;
		}
	}
}
