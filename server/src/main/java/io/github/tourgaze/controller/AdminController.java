/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.tourgaze.dto.IntegrityReportDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.service.RideExportService;
import io.github.tourgaze.store.StorageService;

/**
 * Maintenance endpoints — cache purge, disk stats, etc.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	private static final Logger log = LoggerFactory.getLogger(AdminController.class);

	private final StorageService storage;
	private final RideExportService rideExport;
	private final io.github.tourgaze.service.RideRecoveryService rideRecovery;
	private final io.github.tourgaze.repository.ActivityRepository activityRepo;
	private final io.github.tourgaze.service.RideMetadataMapper metadataMapper;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

	public AdminController(StorageService storage, RideExportService rideExport,
			io.github.tourgaze.service.RideRecoveryService rideRecovery,
			io.github.tourgaze.repository.ActivityRepository activityRepo,
			io.github.tourgaze.service.RideMetadataMapper metadataMapper,
			com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
		this.storage = storage;
		this.rideExport = rideExport;
		this.rideRecovery = rideRecovery;
		this.activityRepo = activityRepo;
		this.metadataMapper = metadataMapper;
		this.objectMapper = objectMapper;
	}

	/**
	 * DB ↔ store integrity check (matros-style, read-only): finds activities whose
	 * ride file is missing, whose content no longer matches its recorded
	 * {@code sourceHash} (bit-rot / cloud-sync damage — decrypted first when
	 * encryption is on), and {@code store/<id>/} folders with no activity.
	 */
	@GetMapping("/integrity")
	@Transactional(readOnly = true)
	public IntegrityReportDto integrity() {
		List<Activity> all = activityRepo.findAll();
		List<IntegrityReportDto.Ref> missing = new ArrayList<>();
		List<IntegrityReportDto.Ref> corrupt = new ArrayList<>();
		Set<String> dbIds = new HashSet<>();

		for (Activity a : all) {
			dbIds.add(a.getId());
			String sf = a.getSourceFilename();
			if (sf == null || !Files.exists(storage.storeFileOnDisk(sf))) {
				missing.add(new IntegrityReportDto.Ref(a.getId(), a.getName()));
				continue;
			}
			if (a.getSourceHash() != null) {
				try {
					if (!a.getSourceHash().equalsIgnoreCase(sha256Hex(storage.readStoreBytes(sf))))
						corrupt.add(new IntegrityReportDto.Ref(a.getId(), a.getName()));
				} catch (Exception e) {
					log.warn("[Integrity] unreadable file for {} ({}): {}", a.getId(), sf, e.getMessage());
					corrupt.add(new IntegrityReportDto.Ref(a.getId(), a.getName()));
				}
			}
		}

		List<String> orphans = new ArrayList<>();
		try (var dirs = Files.list(storage.storeDir())) {
			dirs.filter(Files::isDirectory)
					.map(p -> p.getFileName().toString())
					.filter(id -> !dbIds.contains(id))
					.sorted()
					.forEach(orphans::add);
		} catch (IOException e) {
			log.warn("[Integrity] store scan failed: {}", e.getMessage());
		}
		log.info("[Integrity] {} activities · {} missing · {} corrupt · {} orphan folders",
				all.size(), missing.size(), corrupt.size(), orphans.size());
		return new IntegrityReportDto(all.size(), missing, corrupt, orphans);
	}

	/**
	 * Delete orphan {@code store/<id>/} folders — those whose id is not a current
	 * activity (leftovers from earlier import generations). These hold no data not
	 * already in a live ride, but they waste disk and clutter recovery scans.
	 * Returns how many folders were removed and how many bytes freed.
	 */
	@PostMapping("/purge-orphans")
	@Transactional(readOnly = true)
	public ResponseEntity<Map<String, Object>> purgeOrphans() {
		Set<String> dbIds = new HashSet<>();
		for (Activity a : activityRepo.findAll())
			dbIds.add(a.getId());
		int removed = 0;
		long bytes = 0;
		try (var dirs = Files.list(storage.storeDir())) {
			List<java.nio.file.Path> orphanDirs = dirs.filter(Files::isDirectory)
					.filter(p -> !dbIds.contains(p.getFileName().toString()))
					.toList();
			for (java.nio.file.Path d : orphanDirs) {
				try {
					bytes += deleteTree(d);
					removed++;
				} catch (IOException e) {
					log.warn("[Purge] could not delete orphan {}: {}", d, e.getMessage());
				}
			}
		} catch (IOException e) {
			log.warn("[Purge] store scan failed: {}", e.getMessage());
		}
		log.info("[Purge] removed {} orphan folder(s), freed {} bytes", removed, bytes);
		return ResponseEntity.ok(Map.of("removed", removed, "bytesFreed", bytes));
	}

	/** Recursively delete a directory tree, returning the total bytes removed. */
	private static long deleteTree(java.nio.file.Path root) throws IOException {
		long[] total = { 0 };
		try (var walk = Files.walk(root)) {
			List<java.nio.file.Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
			for (java.nio.file.Path p : paths) {
				if (Files.isRegularFile(p))
					total[0] += Files.size(p);
				Files.deleteIfExists(p);
			}
		}
		return total[0];
	}

	private static String sha256Hex(byte[] data) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
			StringBuilder hex = new StringBuilder(2 * hash.length);
			for (byte b : hash) {
				String h = Integer.toHexString(0xff & b);
				if (h.length() == 1)
					hex.append('0');
				hex.append(h);
			}
			return hex.toString();
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	/**
     * Download a ZIP that recreates every ride's ORIGINAL dropped file (original
     * name + content) alongside its metadata sidecar. Handy as a plaintext escape
     * hatch — especially with storage encryption on, since the bytes are read
     * back out through the store decrypted. Re-importable: unzip into the inbox.
     *
     * @param includeMedia when true, each ride's photos/videos (decrypted) and its
     *                     {@code media.json} manifest are bundled under a per-ride
     *                     {@code <base>_media/} folder. Off by default — the export
     *                     stays small/fast unless you want the full library.
     */
    @org.springframework.web.bind.annotation.GetMapping("/export")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportAll(
            @org.springframework.web.bind.annotation.RequestParam(name = "media", defaultValue = "false") boolean includeMedia) {
        // Materialise everything inside the tx (DTOs resolve lazy gear/rider/tags
        // to plain strings) so the post-return streaming never touches a closed session.
        record Item(String name, String sf, byte[] meta, java.util.List<String> mediaNames) {}
        java.util.List<Item> items = new java.util.ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (var a : activityRepo.findAll()) {
            String sf = a.getSourceFilename();
            if (!Files.exists(storage.storeFileOnDisk(sf))) continue;
            String orig = (a.getOriginalFilename() != null && !a.getOriginalFilename().isBlank())
                    ? a.getOriginalFilename() : sf;
            String name = uniqueName(orig, used);
            byte[] meta;
            try {
                meta = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(metadataMapper.toDto(a, java.time.Instant.now()));
            } catch (Exception e) { meta = new byte[0]; }
            java.util.List<String> mediaNames = java.util.List.of();
            if (includeMedia) {
                try {
                    mediaNames = storage.listLogicalNames(storage.activityMediaDir(sf));
                } catch (Exception e) { /* no media dir → none */ }
            }
            items.add(new Item(name, sf, meta, mediaNames));
        }

        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            try (var zip = new java.util.zip.ZipOutputStream(out)) {
                for (Item it : items) {
                    int dot = it.name().lastIndexOf('.');
                    String base = dot > 0 ? it.name().substring(0, dot) : it.name();
                    zip.putNextEntry(new java.util.zip.ZipEntry(it.name()));
                    try (var in = storage.openStore(it.sf())) { in.transferTo(zip); }
                    zip.closeEntry();
                    if (it.meta().length > 0) {
                        zip.putNextEntry(new java.util.zip.ZipEntry(base + ".metadata.json"));
                        zip.write(it.meta());
                        zip.closeEntry();
                    }
                    if (!it.mediaNames().isEmpty()) {
                        java.nio.file.Path mediaDir = storage.activityMediaDir(it.sf());
                        for (String mn : it.mediaNames()) {
                            zip.putNextEntry(new java.util.zip.ZipEntry(base + "_media/" + mn));
                            try (var in = storage.openEncrypted(mediaDir.resolve(mn))) { in.transferTo(zip); }
                            zip.closeEntry();
                        }
                    }
                }
            }
        };
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"tourgaze-export.zip\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                .body(body);
    }

	/**
	 * Disambiguate duplicate original filenames by inserting _2, _3, … before the
	 * extension.
	 */
	private static String uniqueName(String name, java.util.Set<String> used) {
		if (used.add(name))
			return name;
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 2;; i++) {
			String candidate = base + "_" + i + ext;
			if (used.add(candidate))
				return candidate;
		}
	}

	/**
	 * Force a full ride-metadata sidecar export now (otherwise runs on startup +
	 * nightly).
	 */
	@PostMapping("/export-metadata")
	public ResponseEntity<Map<String, Object>> exportMetadata() {
		int written = rideExport.exportAllNow();
		return ResponseEntity.ok(Map.of("written", written));
	}

	/**
	 * Disaster recovery: rebuild missing DB rows from the {@code .metadata.json}
	 * sidecars in store/. Re-parses each ride's source track for the recomputable
	 * fields and reapplies the sidecar metadata (gear/tags/rider by name,
	 * events/attributes, weight). Idempotent — rides already in the DB are
	 * skipped, so it's safe to run against a fresh or partial DB.
	 */
	@PostMapping("/recover")
	public ResponseEntity<io.github.tourgaze.service.RideRecoveryService.RecoveryReport> recover() {
		return ResponseEntity.ok(rideRecovery.recoverAll());
	}

	/**
	 * Delete all files in the cache/ directory (JSON track caches).
	 * The cache is rebuilt lazily on the next track request — no data is lost.
	 */
	@DeleteMapping("/cache")
	@CacheEvict(value = "diskUsage", allEntries = true)
	public ResponseEntity<Map<String, Object>> purgeCache() {
		AtomicLong deleted = new AtomicLong();
		AtomicLong errors = new AtomicLong();

		try (var stream = Files.list(storage.cacheDir())) {
			stream.filter(Files::isRegularFile).forEach(p -> {
				try {
					Files.delete(p);
					deleted.incrementAndGet();
				} catch (IOException e) {
					log.warn("Could not delete cache file {}: {}", p, e.getMessage());
					errors.incrementAndGet();
				}
			});
		} catch (IOException e) {
			return ResponseEntity.internalServerError()
					.body(Map.of("error", "Could not list cache directory: " + e.getMessage()));
		}

		log.info("Cache purged: {} files deleted, {} errors", deleted.get(), errors.get());
		return ResponseEntity.ok(Map.of("deleted", deleted.get(), "errors", errors.get()));
	}

	/**
	 * Open the repository folder (store/ + db-backup/) in the OS file manager —
	 * a local-desktop convenience. Cross-platform: Explorer on Windows, {@code
	 * open} on macOS, {@code xdg-open} on Linux. The path is resolved server-side
	 * (never from the client), and it's a no-op with a clear message when running
	 * headless / in a container where there's no desktop.
	 */
	@PostMapping("/open-folder")
	public ResponseEntity<Map<String, Object>> openFolder() {
		java.nio.file.Path dir = storage.repositoryDir();
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			/* fall through — open will report if it truly doesn't exist */ }
		if (isHeadless()) {
			return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
					.body(Map.of("error", "No desktop available (running headless or in a container).",
							"path", dir.toString()));
		}
		try {
			String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
			ProcessBuilder pb = os.contains("win") ? new ProcessBuilder("explorer.exe", dir.toString())
					: os.contains("mac") ? new ProcessBuilder("open", dir.toString())
							: new ProcessBuilder("xdg-open", dir.toString());
			// Fire and forget — don't check the exit code: Windows Explorer returns 1
			// even when it opens fine.
			pb.start();
			log.info("Opened repository folder {}", dir);
			return ResponseEntity.ok(Map.of("opened", true, "path", dir.toString()));
		} catch (Exception e) {
			log.warn("Could not open repository folder {}: {}", dir, e.getMessage());
			return ResponseEntity.internalServerError()
					.body(Map.of("error", String.valueOf(e.getMessage()), "path", dir.toString()));
		}
	}

	/**
	 * Docker/Kubernetes (and other headless hosts) have no file manager to open.
	 */
	private boolean isHeadless() {
		return Files.exists(java.nio.file.Path.of("/.dockerenv"))
				|| System.getenv("KUBERNETES_SERVICE_HOST") != null
				|| System.getenv("CONTAINER") != null;
	}

	/**
	 * Disk usage summary for the data directory plus the on-disk locations of the
	 * precious library (repository root + the user-info sidecar), so the Storage
	 * settings page can show <em>where</em> profile/gear actually live. 30s TTL via
	 * Caffeine.
	 */
	@org.springframework.web.bind.annotation.GetMapping("/disk")
	@Cacheable("diskUsage")
	public ResponseEntity<Map<String, Object>> diskUsage() {
		try {
			long store = directorySize(storage.storeDir());
			long cache = directorySize(storage.cacheDir());
			long tiles = directorySize(storage.tilesDir());
			Map<String, Object> body = new java.util.LinkedHashMap<>();
			body.put("storeBytes", store);
			body.put("cacheBytes", cache);
			body.put("tilesBytes", tiles);
			body.put("totalBytes", store + cache + tiles);
			// Resolved server-side — the repository root and the library sidecar
			// (profiles + full gear list) that recovery rebuilds the library from.
			body.put("repositoryDir", storage.repositoryDir().toString());
			body.put("libraryFile", storage.storeDir().resolve("library.metadata.json").toString());
			return ResponseEntity.ok(body);
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	private long directorySize(java.nio.file.Path dir) throws IOException {
		if (!Files.exists(dir))
			return 0L;
		try (var stream = Files.walk(dir)) {
			return stream.filter(Files::isRegularFile)
					.mapToLong(p -> {
						try {
							return Files.size(p);
						} catch (IOException e) {
							return 0L;
						}
					}).sum();
		}
	}
}
