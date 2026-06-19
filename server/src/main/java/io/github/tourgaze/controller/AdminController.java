/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
	private final io.github.tourgaze.repository.ActivityRepository activityRepo;
	private final io.github.tourgaze.service.RideMetadataMapper metadataMapper;
	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

	public AdminController(StorageService storage, RideExportService rideExport,
			io.github.tourgaze.repository.ActivityRepository activityRepo,
			io.github.tourgaze.service.RideMetadataMapper metadataMapper,
			com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
		this.storage = storage;
		this.rideExport = rideExport;
		this.activityRepo = activityRepo;
		this.metadataMapper = metadataMapper;
		this.objectMapper = objectMapper;
	}

	/**
     * Download a ZIP that recreates every ride's ORIGINAL dropped file (original
     * name + content) alongside its metadata sidecar. Handy as a plaintext escape
     * hatch — especially with storage encryption on, since the bytes are read
     * back out through the store decrypted. Re-importable: unzip into the inbox.
     */
    @org.springframework.web.bind.annotation.GetMapping("/export")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> exportAll() {
        // Materialise everything inside the tx (DTOs resolve lazy gear/rider/tags
        // to plain strings) so the post-return streaming never touches a closed session.
        record Item(String name, java.nio.file.Path src, byte[] meta) {}
        java.util.List<Item> items = new java.util.ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (var a : activityRepo.findAll()) {
            java.nio.file.Path src = storage.storeFile(a.getSourceFilename());
            if (!Files.exists(src)) continue;
            String orig = (a.getOriginalFilename() != null && !a.getOriginalFilename().isBlank())
                    ? a.getOriginalFilename() : a.getSourceFilename();
            String name = uniqueName(orig, used);
            byte[] meta;
            try {
                meta = objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsBytes(metadataMapper.toDto(a, java.time.Instant.now()));
            } catch (Exception e) { meta = new byte[0]; }
            items.add(new Item(name, src, meta));
        }

        org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody body = out -> {
            try (var zip = new java.util.zip.ZipOutputStream(out)) {
                for (Item it : items) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(it.name()));
                    Files.copy(it.src(), zip);
                    zip.closeEntry();
                    if (it.meta().length > 0) {
                        int dot = it.name().lastIndexOf('.');
                        String base = dot > 0 ? it.name().substring(0, dot) : it.name();
                        zip.putNextEntry(new java.util.zip.ZipEntry(base + ".metadata.json"));
                        zip.write(it.meta());
                        zip.closeEntry();
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

	/** Disk usage summary for the data directory. 30s TTL via Caffeine. */
	@org.springframework.web.bind.annotation.GetMapping("/disk")
	@Cacheable("diskUsage")
	public ResponseEntity<Map<String, Long>> diskUsage() {
		try {
			long store = directorySize(storage.storeDir());
			long cache = directorySize(storage.cacheDir());
			long tiles = directorySize(storage.tilesDir());
			return ResponseEntity.ok(Map.of(
					"storeBytes", store,
					"cacheBytes", cache,
					"tilesBytes", tiles,
					"totalBytes", store + cache + tiles));
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
