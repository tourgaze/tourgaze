/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import io.github.tourgaze.config.AppConfig;

/**
 * Single source of truth for all on-disk locations.
 *
 * Two roots, so the precious library can sync to the cloud while regenerable
 * data stays local (matros repository-vs-workspace split):
 *
 * repositoryDir/ (cloud-syncable; default = dataDir/repository)
 * store/ ← processed ride files + photos + metadata sidecars (permanent)
 * db-backup/ ← H2 snapshot zips (safe to cloud-sync; off-machine DB backup)
 *
 * dataDir/ (local; default ~/.tourgaze/)
 * inbox/ ← drop FIT files here; watcher picks them up
 * cache/ ← per-activity JSON track cache (lazy, rebuildable from store)
 * tiles/z/x/y.png ← cached OSM tile images (re-downloadable)
 * db/ ← H2 database (local; rebuildable from the store sidecars)
 */
@Service
public class StorageService {

	private static final Logger log = LoggerFactory.getLogger(StorageService.class);

	private final Path baseDir;
	private final Path repoDir;

	public StorageService(AppConfig config) {
		this.baseDir = Path.of(config.getDataDir());
		this.repoDir = Path.of(config.getRepositoryDir());
	}

	@PostConstruct
	public void init() {
		try {
			// matros split: the precious folders (store, db-backup) live under repoDir
			// so a single folder cloud-syncs cleanly; the regenerable workspace stays
			// under baseDir. Installs predating the split kept these directly under
			// baseDir — move them into repoDir once so existing rides/backups aren't
			// orphaned by the new default location.
			if (!repoDir.equals(baseDir)) {
				migrateLegacyRepoFolder("store");
				migrateLegacyRepoFolder("db-backup");
			}
			Files.createDirectories(inboxDir());
			Files.createDirectories(storeDir());
			Files.createDirectories(cacheDir());
			Files.createDirectories(tilesDir());
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to create storage directories", e);
		}
	}

	/**
	 * One-time relocation of a precious folder from the pre-split location
	 * (directly under dataDir) into repositoryDir. No-op when the legacy folder is
	 * absent or the destination already exists, so it's safe to run every boot.
	 */
	private void migrateLegacyRepoFolder(String name) throws IOException {
		Path legacy = baseDir.resolve(name);
		Path target = repoDir.resolve(name);
		if (!Files.isDirectory(legacy) || Files.exists(target))
			return;
		Files.createDirectories(target.getParent());
		try {
			Files.move(legacy, target);
		} catch (IOException renameFailed) {
			// repoDir on a different drive (e.g. a cloud folder): copy then delete.
			copyTree(legacy, target);
			deleteTree(legacy);
		}
		log.info("[Storage] Migrated legacy {}/ into repository dir {}", name, repoDir);
	}

	private static void copyTree(Path src, Path dst) throws IOException {
		try (Stream<Path> walk = Files.walk(src)) {
			for (Path p : (Iterable<Path>) walk::iterator) {
				Path out = dst.resolve(src.relativize(p));
				if (Files.isDirectory(p))
					Files.createDirectories(out);
				else
					Files.copy(p, out, StandardCopyOption.COPY_ATTRIBUTES);
			}
		}
	}

	private static void deleteTree(Path root) throws IOException {
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException ignored) {
					// best-effort: a leftover empty dir is harmless
				}
			});
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

	/**
	 * Archive folder for inbox files the user has dealt with but doesn't want to
	 * import (e.g. a same-track duplicate) — moved here rather than deleted, so
	 * the bytes are never lost. Sibling of inbox/ (matros "processed" convention).
	 */
	public Path inboxProcessedDir() {
		return baseDir.resolve("inbox-processed");
	}

	/** Root of the precious, cloud-syncable library (holds store/ + db-backup/). */
	public Path repositoryDir() {
		return repoDir;
	}

	/** Precious ride store — under repositoryDir so it can sync to the cloud. */
	public Path storeDir() {
		return repoDir.resolve("store");
	}

	public Path cacheDir() {
		return baseDir.resolve("cache");
	}

	public Path tilesDir() {
		return baseDir.resolve("tiles");
	}

	/**
	 * H2 {@code BACKUP TO} zip snapshots. Under repositoryDir so the backups
	 * sync to the cloud — a static zip is safe to sync (written atomically),
	 * unlike the live DB file, so this gives off-machine DB backup.
	 */
	public Path dbBackupDir() {
		return repoDir.resolve("db-backup");
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
