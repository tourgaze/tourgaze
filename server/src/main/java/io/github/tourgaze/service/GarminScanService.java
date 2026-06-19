/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.github.tourgaze.repository.SettingRepository;
import io.github.tourgaze.store.StorageService;

/**
 * Polls the Garmin device directory (e.g. {@code X:\garmin\activity}) and
 * copies new .fit files into our inbox/ for review.
 *
 * Hard rules:
 * 1. The device is the source of truth — we NEVER delete files from it.
 * 2. We never copy a file whose content hash is already imported (it's a
 * duplicate) or already pending in inbox/ (already staged this scan).
 * 3. The scan is a no-op when {@code garmin.device.path} setting is empty.
 *
 * Wires off two settings:
 * garmin.device.path — absolute path to the device folder
 * garmin.scan.interval-sec — informational; the @Scheduled cadence is
 * controlled by a Spring property and defaults
 * to 60 s here.
 */
@Service
public class GarminScanService {

	private static final Logger log = LoggerFactory.getLogger(GarminScanService.class);

	private final StorageService storage;
	private final SettingRepository settingRepo;
	private final InboxService inboxService;

	public GarminScanService(StorageService storage,
			SettingRepository settingRepo,
			InboxService inboxService) {
		this.storage = storage;
		this.settingRepo = settingRepo;
		this.inboxService = inboxService;
	}

	@Scheduled(fixedDelayString = "${tourgaze.garmin-scan-ms:60000}", initialDelay = 8000)
	public void scan() {
		String configured = settingRepo.findById("garmin.device.path")
				.map(s -> s.getValue())
				.orElse("");
		if (configured == null || configured.isBlank())
			return;

		Path devicePath;
		try {
			devicePath = Path.of(configured);
		} catch (Exception e) {
			log.warn("Invalid garmin.device.path '{}': {}", configured, e.getMessage());
			return;
		}
		if (!Files.isDirectory(devicePath)) {
			// Device unplugged or wrong path — silent skip, very common.
			return;
		}

		int copied = 0, skipped = 0;
		try (var stream = Files.list(devicePath)) {
			for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
					.filter(this::isFit)::iterator) {
				if (processOne(file))
					copied++;
				else
					skipped++;
			}
		} catch (IOException e) {
			log.warn("Garmin scan failed at {}: {}", devicePath, e.getMessage());
			return;
		}

		if (copied > 0) {
			log.info("Garmin scan: copied {} new file(s), skipped {} duplicate(s) from {}",
					copied, skipped, devicePath);
		}
	}

	private boolean processOne(Path source) {
		try {
			byte[] data = Files.readAllBytes(source);
			String sha = inboxService.hashOf(data);

			if (inboxService.isAlreadyImported(sha))
				return false;
			if (inboxService.isAlreadyStaged(sha))
				return false;

			// Copy (not move!) — the device stays untouched.
			Path target = storage.inboxDir().resolve(safeName(source.getFileName().toString()));
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			log.warn("Garmin scan: could not copy {}: {}", source, e.getMessage());
			return false;
		}
	}

	private boolean isFit(Path p) {
		return p.toString().toLowerCase(Locale.ROOT).endsWith(".fit");
	}

	/**
	 * Add a short prefix so multiple devices / repeated scans never collide on
	 * the same source filename inside our inbox.
	 */
	private String safeName(String original) {
		String stamp = Long.toHexString(System.currentTimeMillis());
		return "garmin-" + stamp + "-" + original;
	}

	/** Exposed for the StorageService init order. */
	public Optional<String> currentDevicePath() {
		return settingRepo.findById("garmin.device.path").map(s -> s.getValue());
	}
}
