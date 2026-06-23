/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.LibraryMetadataDto;
import io.github.tourgaze.dto.RideMetadataDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.event.ActivityEvents;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.repository.UserRepository;
import io.github.tourgaze.service.mapper.GearMapper;
import io.github.tourgaze.service.mapper.UserMapper;
import io.github.tourgaze.store.StorageService;

/**
 * Keeps a per-ride metadata sidecar ({@code <source-basename>.metadata.json},
 * next to the source file in store/) in sync for disaster recovery and easy
 * external use. Each sidecar matches {@link RideMetadataDto}.
 *
 * Two triggers:
 * - <b>On change</b> (primary): an
 * {@link ActivityEvents.Changed}/{@code Removed}
 * fired by the service layer is handled AFTER COMMIT and async, so the sidecar
 * is rewritten the instant a ride is imported / edited / deleted, off the
 * request thread and reflecting committed state.
 * - <b>Startup + nightly</b> (reconcile): a full sweep rewrites every sidecar
 * and prunes orphans, catching anything the event path missed (e.g. manual
 * file edits) — belt and braces.
 */
@Service
@Lazy(false)
public class RideExportService {

	private static final Logger log = LoggerFactory.getLogger(RideExportService.class);

	private final ActivityRepository activityRepo;
	private final StorageService storage;
	private final ObjectMapper objectMapper;
	private final RideMetadataMapper mapper;
	private final UserRepository userRepo;
	private final GearRepository gearRepo;
	private final UserMapper userMapper;
	private final GearMapper gearMapper;

	@Value("${tourgaze.export.metadata.enabled:true}")
	private boolean enabled;

	public RideExportService(ActivityRepository activityRepo, StorageService storage,
			ObjectMapper objectMapper, RideMetadataMapper mapper, UserRepository userRepo,
			GearRepository gearRepo, UserMapper userMapper, GearMapper gearMapper) {
		this.activityRepo = activityRepo;
		this.storage = storage;
		this.objectMapper = objectMapper;
		this.mapper = mapper;
		this.userRepo = userRepo;
		this.gearRepo = gearRepo;
		this.userMapper = userMapper;
		this.gearMapper = gearMapper;
	}

	// ── On-change (primary) ──────────────────────────────────────────────────
	@Async
	// AFTER_COMMIT runs after the originating tx closed, so we need a fresh tx
	// (plain @Transactional would try to join the committed one — not allowed).
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW, readOnly = true)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onActivityChanged(ActivityEvents.Changed e) {
		if (!enabled)
			return;
		activityRepo.findById(e.activityId()).ifPresent(a -> {
			try {
				writeSidecar(a);
			} catch (Exception ex) {
				log.warn("[Export] On-change export failed for {}: {}", e.activityId(), ex.getMessage());
			}
		});
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onActivityRemoved(ActivityEvents.Removed e) {
		if (!enabled)
			return;
		try {
			Files.deleteIfExists(sidecarPath(e.sourceFilename()));
		} catch (Exception ex) {
			log.warn("[Export] Sidecar delete failed for {}: {}", e.sourceFilename(), ex.getMessage());
		}
	}

	// ── Startup: library sidecar only (cheap insurance) ──────────────────────
	// Per-ride sidecars are kept fresh on-change (above), so we no longer rewrite
	// all of them on every boot or nightly — that bulk churn was wasteful. We do
	// keep the tiny library sidecar (rider + gear) current on startup, since the
	// rider/gear can't be reconstructed from ride files. A full reconcile is
	// available on demand via exportAllNow() (Settings → "Back up metadata now").
	@Async
	@Transactional(readOnly = true)
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!enabled)
			return;
		try {
			writeLibrarySidecar();
		} catch (Exception e) {
			log.warn("[Export] Startup library sidecar failed: {}", e.getMessage());
		}
	}

	/**
	 * Refresh just the library sidecar (rider profiles + gear) off-thread — called
	 * when a user or gear row changes, so it never goes stale between full exports.
	 */
	@Async
	@Transactional(readOnly = true)
	public void exportLibraryAsync() {
		if (!enabled)
			return;
		try {
			writeLibrarySidecar();
		} catch (Exception e) {
			log.warn("[Export] Library sidecar refresh failed: {}", e.getMessage());
		}
	}

	/** Manual full export (admin endpoint). Returns rides written. */
	@Transactional(readOnly = true)
	public int exportAllNow() {
		return exportAll("manual");
	}

	private int exportAll(String reason) {
		int ok = 0, failed = 0;
		for (Activity a : activityRepo.findAll()) {
			try {
				writeSidecar(a);
				ok++;
			} catch (Exception e) {
				failed++;
				log.warn("[Export] Failed for {}: {}", a.getId(), e.getMessage());
			}
		}
		try {
			writeLibrarySidecar();
		} catch (Exception e) {
			log.warn("[Export] Library sidecar failed: {}", e.getMessage());
		}
		pruneOrphans();
		log.info("[Export] {} metadata sidecars written ({}), {} failed", ok, reason, failed);
		return ok;
	}

	/**
	 * Write the library sidecar (rider profiles + full gear list) so recovery can
	 * restore the user and any gear not attached to a ride.
	 */
	private void writeLibrarySidecar() throws Exception {
		var users = userRepo.findAll().stream().map(userMapper::toDto).toList();
		var gear = gearRepo.findAll().stream().map(gearMapper::toDto).toList();
		LibraryMetadataDto dto = new LibraryMetadataDto(
				LibraryMetadataDto.SCHEMA_VERSION, Instant.now(), users, gear);
		Path file = storage.storeDir().resolve("library.metadata.json");
		Files.createDirectories(file.getParent());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), dto);
	}

	// ── Write / path helpers ─────────────────────────────────────────────────
	private void writeSidecar(Activity a) throws Exception {
		RideMetadataDto dto = mapper.toDto(a, Instant.now());
		Path sidecar = sidecarPath(a.getSourceFilename());
		Files.createDirectories(sidecar.getParent()); // the ride's store/<id>/ folder
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(sidecar.toFile(), dto);
	}

	/**
	 * store/<rideId>/<name>.metadata.json (plaintext sidecar, beside the track).
	 */
	private Path sidecarPath(String sourceFilename) {
		int dot = sourceFilename.lastIndexOf('.');
		String base = dot > 0 ? sourceFilename.substring(0, dot) : sourceFilename;
		return storage.storeDir().resolve(base + ".metadata.json");
	}

	/**
	 * Drop per-ride sidecars whose source file (and thus ride) is gone, so the
	 * export mirrors the store. Per-ride sidecars live one level down in
	 * {@code store/<id>/}, so we walk the tree (the previous top-level-only scan
	 * never saw them — and wrongly treated the top-level library sidecar as an
	 * orphan). The library sidecar is skipped: it intentionally has no source file.
	 */
	private void pruneOrphans() {
		Path root = storage.storeDir();
		try (var walk = Files.walk(root, 2)) {
			List<Path> sidecars = walk
					.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".metadata.json"))
					.filter(p -> !p.getFileName().toString().equals("library.metadata.json"))
					.toList();
			for (Path p : sidecars) {
				String base = p.getFileName().toString().replaceFirst("\\.metadata\\.json$", "");
				Path folder = p.getParent();
				boolean hasSource;
				try (var siblings = Files.list(folder)) {
					hasSource = siblings.anyMatch(s -> {
						String n = s.getFileName().toString();
						return !n.endsWith(".metadata.json") && (n.equals(base) || n.startsWith(base + "."));
					});
				} catch (Exception ignored) {
					hasSource = true;
				}
				if (!hasSource)
					try {
						Files.deleteIfExists(p);
					} catch (Exception ignored) {
						/* best-effort */ }
			}
		} catch (Exception e) {
			log.debug("[Export] Orphan prune skipped: {}", e.getMessage());
		}
	}
}
