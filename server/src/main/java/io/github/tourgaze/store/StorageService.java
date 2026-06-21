/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.store;

import java.io.IOException;
import java.io.InputStream;
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
import io.github.tourgaze.store.encryption.EncryptionConfig;
import io.github.tourgaze.store.encryption.EncryptionService;

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
	private final EncryptionService enc;
	private final EncryptionConfig crypto;

	public StorageService(AppConfig config, EncryptionService enc, EncryptionConfig crypto) {
		this.baseDir = Path.of(config.getDataDir());
		this.repoDir = Path.of(config.getRepositoryDir());
		this.enc = enc;
		this.crypto = crypto;
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
	 * A ride's own folder: {@code store/<activityId>/} (track + media + sidecar).
	 */
	public Path rideDir(String activityId) {
		validateFilename(activityId);
		return storeDir().resolve(activityId);
	}

	/**
	 * A ride's media folder — {@code store/<rideId>/media}, beside its track file.
	 * Derived from the source path ({@code "<rideId>/<name>"}) so callers holding
	 * only the sourceFilename still resolve it.
	 */
	public Path activityMediaDir(String sourceFilename) {
		Path parent = storeFile(sourceFilename).getParent();
		return (parent != null ? parent : storeDir()).resolve("media");
	}

	/**
	 * Logical path of a store file (no {@code .enc}). {@code relName} is the
	 * sourceFilename, which is {@code "<rideId>/<name>"}. Validated against
	 * traversal.
	 */
	public Path storeFile(String relName) {
		validateRelPath(relName);
		return storeDir().resolve(relName);
	}

	/** The actual on-disk file — the {@code .enc} variant when it exists. */
	public Path storeFileOnDisk(String relName) {
		Path logical = storeFile(relName);
		Path encrypted = logical.resolveSibling(logical.getFileName() + ".enc");
		return Files.exists(encrypted) ? encrypted : logical;
	}

	/**
	 * Move an inbox file into the store, encrypting it (→ {@code .enc}) when on.
	 */
	public void moveIntoStore(Path source, String relName) throws IOException {
		Path logical = storeFile(relName);
		Files.createDirectories(logical.getParent());
		if (crypto.isEncryptionEnabled()) {
			Path encrypted = logical.resolveSibling(logical.getFileName() + ".enc");
			enc.encryptFile(source, encrypted, crypto.getKey());
			Files.deleteIfExists(source);
		} else {
			Files.move(source, logical, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Read a store file's bytes, transparently decrypting an {@code .enc} variant.
	 */
	public byte[] readStoreBytes(String relName) throws IOException {
		Path logical = storeFile(relName);
		Path encrypted = logical.resolveSibling(logical.getFileName() + ".enc");
		if (crypto.isEncryptionEnabled() && Files.exists(encrypted))
			return enc.decryptBytes(encrypted, crypto.getKey());
		return Files.readAllBytes(logical);
	}

	/** Open a store file for streaming, transparently decrypting {@code .enc}. */
	public InputStream openStore(String relName) throws IOException {
		Path logical = storeFile(relName);
		Path encrypted = logical.resolveSibling(logical.getFileName() + ".enc");
		if (crypto.isEncryptionEnabled() && Files.exists(encrypted))
			return enc.decryptStream(encrypted, crypto.getKey());
		return Files.newInputStream(logical);
	}

	/** Delete a ride's entire folder (track + media + sidecar) in one shot. */
	public void deleteRide(String activityId) throws IOException {
		Path dir = rideDir(activityId);
		if (Files.isDirectory(dir))
			deleteTree(dir);
	}

	// ── Encryption-aware ops on a LOGICAL path (the .enc suffix is handled here).
	// Used for per-ride media (store/<id>/media/<name>): the image/video bytes are
	// encrypted; the media.json manifest stays plaintext (it's just metadata). ──

	private Path encOf(Path logical) {
		return logical.resolveSibling(logical.getFileName() + ".enc");
	}

	/**
	 * Write bytes to {@code logical}, encrypting to {@code <logical>.enc} when on.
	 */
	public void writeEncrypted(Path logical, byte[] data) throws IOException {
		Files.createDirectories(logical.getParent());
		if (crypto.isEncryptionEnabled())
			enc.encryptBytes(data, encOf(logical), crypto.getKey());
		else
			Files.write(logical, data);
	}

	/** Read {@code logical}, transparently decrypting an {@code .enc} variant. */
	public byte[] readEncrypted(Path logical) throws IOException {
		Path e = encOf(logical);
		if (crypto.isEncryptionEnabled() && Files.exists(e))
			return enc.decryptBytes(e, crypto.getKey());
		return Files.readAllBytes(logical);
	}

	/** Stream {@code logical}, transparently decrypting an {@code .enc} variant. */
	public InputStream openEncrypted(Path logical) throws IOException {
		Path e = encOf(logical);
		if (crypto.isEncryptionEnabled() && Files.exists(e))
			return enc.decryptStream(e, crypto.getKey());
		return Files.newInputStream(logical);
	}

	/** True if {@code logical} (or its {@code .enc} variant) exists. */
	public boolean encryptedExists(Path logical) {
		return Files.exists(logical) || Files.exists(encOf(logical));
	}

	/** Delete {@code logical} and its {@code .enc} variant. */
	public void deleteEncrypted(Path logical) throws IOException {
		Files.deleteIfExists(logical);
		Files.deleteIfExists(encOf(logical));
	}

	/**
	 * Rename a (possibly encrypted) file within {@code dir}, keeping the suffix.
	 */
	public void renameEncrypted(Path dir, String oldName, String newName) throws IOException {
		Path oldEnc = dir.resolve(oldName + ".enc");
		if (Files.exists(oldEnc))
			Files.move(oldEnc, dir.resolve(newName + ".enc"), StandardCopyOption.REPLACE_EXISTING);
		Path oldPlain = dir.resolve(oldName);
		if (Files.exists(oldPlain))
			Files.move(oldPlain, dir.resolve(newName), StandardCopyOption.REPLACE_EXISTING);
	}

	/**
	 * Logical filenames in {@code dir} ({@code .enc} stripped); empty if no dir.
	 */
	public java.util.List<String> listLogicalNames(Path dir) throws IOException {
		if (!Files.isDirectory(dir))
			return java.util.List.of();
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(Files::isRegularFile)
					.map(p -> {
						String n = p.getFileName().toString();
						return n.endsWith(".enc") ? n.substring(0, n.length() - 4) : n;
					})
					.sorted().toList();
		}
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

	/**
	 * A store-relative path ({@code "<rideId>/<name>"}) — allows forward-slash
	 * segments but still rejects traversal, backslashes and blank/absolute parts.
	 */
	private void validateRelPath(String rel) {
		if (rel == null || rel.isBlank() || rel.contains("..") || rel.contains("\\") || rel.startsWith("/")) {
			throw new IllegalArgumentException("Invalid path: " + rel);
		}
		for (String seg : rel.split("/")) {
			if (seg.isBlank())
				throw new IllegalArgumentException("Invalid path: " + rel);
		}
	}

	/** Provider IDs may only contain letters, digits, hyphens and underscores. */
	public static void validateProvider(String provider) {
		if (provider == null || !provider.matches("[a-zA-Z0-9_-]{1,64}")) {
			throw new IllegalArgumentException("Invalid provider id: " + provider);
		}
	}
}
