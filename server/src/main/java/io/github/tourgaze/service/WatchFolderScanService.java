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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.github.tourgaze.repository.SettingRepository;
import io.github.tourgaze.store.StorageService;

/**
 * Polls extra "watch folders" — e.g. a Google Drive folder OpenTracks syncs to,
 * mounted as a Windows drive — and copies new ride files into our inbox/ for
 * review. One newline-separated list lives in the {@code inbox.watch-dirs}
 * setting (edited in Settings → Inbox).
 *
 * Same hard rules as the Garmin scanner: the source is never modified (copy,
 * never move/delete — those are the user's cloud files), and a file already
 * imported (by content hash) or already staged this scan is skipped. Supports
 * every importable format, not just FIT (OpenTracks exports KMZ).
 */
@Service
public class WatchFolderScanService {

	private static final Logger log = LoggerFactory.getLogger(WatchFolderScanService.class);
	private static final Set<String> EXTS = Set.of("fit", "gpx", "tcx", "kmz", "kml");
	public static final String SETTING = "inbox.watch-dirs";

	private final StorageService storage;
	private final SettingRepository settingRepo;
	private final InboxService inboxService;

	public WatchFolderScanService(StorageService storage,
			SettingRepository settingRepo,
			InboxService inboxService) {
		this.storage = storage;
		this.settingRepo = settingRepo;
		this.inboxService = inboxService;
	}

	@Scheduled(fixedDelayString = "${tourgaze.watch-scan-ms:60000}", initialDelay = 10000)
	public void scheduledScan() {
		scanNow();
	}

	/** Scan all configured watch folders; returns how many files were copied in. */
	public int scanNow() {
		int copied = 0;
		for (Path dir : configuredDirs()) {
			if (!Files.isDirectory(dir))
				continue; // unmounted / wrong path — silent skip
			try (var stream = Files.list(dir)) {
				for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
						.filter(WatchFolderScanService::isSupported)::iterator) {
					if (copyIfNew(file))
						copied++;
				}
			} catch (IOException e) {
				log.warn("Watch-folder scan failed at {}: {}", dir, e.getMessage());
			}
		}
		if (copied > 0)
			log.info("Watch-folder scan: copied {} new file(s) into the inbox", copied);
		return copied;
	}

	/** Configured folders, one per line in the {@code inbox.watch-dirs} setting. */
	public List<Path> configuredDirs() {
		String raw = settingRepo.findById(SETTING).map(s -> s.getValue()).orElse("");
		if (raw == null || raw.isBlank())
			return List.of();
		return raw.lines()
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.map(this::toPath)
				.filter(p -> p != null)
				.toList();
	}

	private Path toPath(String s) {
		try {
			return Path.of(s);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean copyIfNew(Path source) {
		try {
			byte[] data = Files.readAllBytes(source);
			String sha = inboxService.hashOf(data);
			if (inboxService.isAlreadyImported(sha) || inboxService.isAlreadyStaged(sha))
				return false;
			// Copy (not move) — the watch folder is a cloud-synced source we must not
			// mutate.
			Path target = storage.inboxDir().resolve(source.getFileName().toString());
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (IOException e) {
			log.warn("Watch-folder scan: could not copy {}: {}", source, e.getMessage());
			return false;
		}
	}

	private static boolean isSupported(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		int dot = n.lastIndexOf('.');
		return dot >= 0 && EXTS.contains(n.substring(dot + 1));
	}
}
