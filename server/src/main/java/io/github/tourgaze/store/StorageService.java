/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PostConstruct;

import org.springframework.stereotype.Service;

import io.github.tourgaze.config.AppConfig;

/**
 * Single source of truth for all on-disk locations.
 *
 * Layout under dataDir (default: ~/.tourgaze/):
 * inbox/ ← drop FIT files here; watcher picks them up
 * store/ ← processed FIT files (UUID-named, permanent)
 * cache/ ← per-activity JSON track cache (lazy, deletable)
 * tiles/z/x/y.png ← cached OSM tile images
 */
@Service
public class StorageService {

	private final Path baseDir;

	public StorageService(AppConfig config) {
		this.baseDir = Path.of(config.getDataDir());
	}

	@PostConstruct
	public void init() {
		try {
			Files.createDirectories(inboxDir());
			Files.createDirectories(storeDir());
			Files.createDirectories(cacheDir());
			Files.createDirectories(tilesDir());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create storage directories", e);
		}
	}

	public Path inboxDir() {
		return baseDir.resolve("inbox");
	}

	/**
	 * Sibling of inbox/ where files the user actively rejected are parked.
	 * They're not deleted (drop-recovery would be lost otherwise) — they just
	 * don't appear in `listPending` anymore. Move back into inbox/ to undo.
	 */
	public Path inboxIgnoredDir() {
		return baseDir.resolve("inbox-ignored");
	}

	public Path storeDir() {
		return baseDir.resolve("store");
	}

	public Path cacheDir() {
		return baseDir.resolve("cache");
	}

	public Path tilesDir() {
		return baseDir.resolve("tiles");
	}

	/**
	 * Photos dropped while staging an inbox file, before it's imported. Keyed by
	 * the inbox filename; moved into {@link #activityMediaDir} on import.
	 */
	public Path inboxMediaDir(String inboxFilename) {
		validateFilename(inboxFilename);
		return baseDir.resolve("inbox-media").resolve(inboxFilename);
	}

	/**
	 * A ride's media folder, next to its source file in store/ —
	 * {@code store/<source-basename>_media/} (e.g. {@code 2024-…_<hash>_media}).
	 * Sits alongside the track + metadata sidecar for file-tree recovery.
	 */
	public Path activityMediaDir(String sourceFilename) {
		validateFilename(sourceFilename);
		int dot = sourceFilename.lastIndexOf('.');
		String base = dot > 0 ? sourceFilename.substring(0, dot) : sourceFilename;
		return storeDir().resolve(base + "_media");
	}

	/**
	 * Resolve a filename inside the store directory.
	 * Validates against path traversal.
	 */
	public Path storeFile(String filename) {
		validateFilename(filename);
		return storeDir().resolve(filename);
	}

	/** Lazy JSON track cache for a given activity ID. */
	public Path cacheFile(String activityId) {
		return cacheDir().resolve(activityId + ".json");
	}

	/**
	 * LTTB-reduced chart cache (~800 pts) for a given activity ID.
	 * Each point carries {@code rawIdx} pointing back into the full-res array.
	 */
	public Path chartCacheFile(String activityId) {
		return cacheDir().resolve(activityId + "-chart.json");
	}

	/**
	 * Tile cache path including provider so different tile sources never collide.
	 * Layout: tiles/{provider}/{z}/{x}/{y}.png
	 */
	public Path tileFile(String provider, int z, int x, int y) {
		validateProvider(provider);
		return tilesDir().resolve(provider + "/" + z + "/" + x + "/" + y + ".png");
	}

	private void validateFilename(String filename) {
		if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
			throw new IllegalArgumentException("Invalid filename: " + filename);
		}
	}

	/** Provider IDs may only contain letters, digits, hyphens and underscores. */
	public static void validateProvider(String provider) {
		if (provider == null || !provider.matches("[a-zA-Z0-9_-]{1,64}")) {
			throw new IllegalArgumentException("Invalid provider id: " + provider);
		}
	}
}
