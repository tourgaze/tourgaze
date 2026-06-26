/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.config.AppConfig;
import io.github.tourgaze.repository.SettingRepository;
import io.github.tourgaze.store.StorageService;

/**
 * Polls the configured "inbox folders" — a Garmin device mount, a Google Drive
 * folder OpenTracks syncs to, etc. — and copies new ride files into our inbox/
 * for review.
 *
 * <p>
 * Folders are resolved in precedence order:
 * <ol>
 * <li>the {@code inbox.sources} DB setting — a JSON array of {@code {label,
 * path}} objects edited in Settings → Inbox. Set here, it always wins;</li>
 * <li>{@code tourgaze.inbox.sources} from application.yaml — a deployment
 * default (e.g. baked into a container), used only when nothing is set in the
 * DB.</li>
 * </ol>
 * Keeping the live list in a single setting row keeps the key/value store valid
 * while still allowing several named folders.
 *
 * <p>
 * Hard rules (unchanged): the source is never modified — copy, never
 * move/delete (these are the user's device / cloud files) — and a file already
 * imported (by content hash) or already staged this scan is skipped. Every
 * importable format is picked up, not just FIT (OpenTracks exports KMZ).
 */
@Service
public class WatchFolderScanService {

	private static final Logger log = LoggerFactory.getLogger(WatchFolderScanService.class);
	private static final Set<String> EXTS = Set.of("fit", "gpx", "tcx", "kmz", "kml");

	public static final String SETTING = "inbox.sources";

	/**
	 * Last-seen availability of each configured source path, so the mount detector
	 * can spot an absent → present flip (a USB drive plugged in, a share mounted).
	 */
	private final Map<String, Boolean> sourcePresent = new ConcurrentHashMap<>();

	/** One configured inbox folder: a display label, an absolute path, and whether
	 *  to keep the original on the device (copy) vs move it out (delete after copy). */
	public record InboxSource(String label, String path, Boolean keepOriginal) {
		/** Default true: never move/delete the user's device files unless opted out. */
		boolean keep() {
			return keepOriginal == null || keepOriginal;
		}
	}

	private final StorageService storage;
	private final SettingRepository settingRepo;
	private final InboxService inboxService;
	private final ObjectMapper objectMapper;
	private final AppConfig appConfig;

	public WatchFolderScanService(StorageService storage,
			SettingRepository settingRepo,
			InboxService inboxService,
			ObjectMapper objectMapper,
			AppConfig appConfig) {
		this.storage = storage;
		this.settingRepo = settingRepo;
		this.inboxService = inboxService;
		this.objectMapper = objectMapper;
		this.appConfig = appConfig;
	}

	@Scheduled(fixedDelayString = "${tourgaze.watch-scan-ms:60000}", initialDelay = 10000)
	public void scheduledScan() {
		scanNow();
	}

	/**
	 * Fast mount detector: every few seconds, check whether each configured source
	 * folder is reachable, and the moment one flips from absent to present — a USB
	 * drive (Garmin) plugged in, a network share coming online — scan immediately
	 * instead of waiting up to 60 s for the regular poll. Cross-platform: it just
	 * tests the configured path, so a Windows drive letter and a Linux mount point
	 * are handled the same. Cheap (a few {@code isDirectory} checks); the first
	 * observation of each path only records a baseline, so startup doesn't double
	 * scan.
	 */
	@Scheduled(fixedDelayString = "${tourgaze.mount-poll-ms:3000}", initialDelay = 4000)
	public void detectNewMounts() {
		boolean appeared = false;
		for (InboxSource src : configuredSources()) {
			Path dir = toPath(src.path());
			boolean present = dir != null && Files.isDirectory(dir);
			Boolean prev = sourcePresent.put(src.path(), present);
			if (prev != null && !prev && present) {
				log.info("[Inbox] source '{}' became available ({}) — scanning now", src.label(), src.path());
				appeared = true;
			}
		}
		if (appeared)
			scanNow();
	}

	/** Scan all configured inbox folders; returns how many files were copied in. */
	public int scanNow() {
		int copied = 0;
		// Rebuild the "already imported / skipped" view from scratch each scan so it
		// reflects exactly what's on the connected sources right now.
		inboxService.clearSkipped();
		// Computed once per scan: hashes of files the user already ignored / moved
		// to processed, so we don't re-copy them from the (untouched) source.
		Set<String> parked = inboxService.parkedHashes();
		for (InboxSource src : configuredSources()) {
			Path dir = toPath(src.path());
			if (dir == null || !Files.isDirectory(dir))
				continue; // unmounted / wrong path — silent skip
			try (var stream = Files.list(dir)) {
				for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
						.filter(WatchFolderScanService::isSupported)
						.filter(WatchFolderScanService::isFullyWritten)::iterator) {
					if (copyIfNew(file, parked, src.keep(), src.label()))
						copied++;
				}
			} catch (IOException e) {
				log.warn("Inbox-folder scan failed at {}: {}", dir, e.getMessage());
			}
		}
		if (copied > 0) {
			log.info("Inbox-folder scan: copied {} new file(s) into the inbox", copied);
			// Push an inbox-changed event; the warm job fills in proposals next tick.
			inboxService.notifyChanged();
		}
		return copied;
	}

	/**
	 * Configured inbox folders, in precedence order: the {@code inbox.sources} DB
	 * setting wins; otherwise the {@code tourgaze.inbox.sources} yaml default.
	 */
	public List<InboxSource> configuredSources() {
		// DB-configured list (set via Settings → Inbox) always wins.
		String raw = settingRepo.findById(SETTING).map(s -> s.getValue()).orElse(null);
		if (raw != null && !raw.isBlank()) {
			try {
				return objectMapper.readValue(raw, new TypeReference<List<InboxSource>>() {
				}).stream()
						.filter(s -> s != null && s.path() != null && !s.path().isBlank())
						.toList();
			} catch (IOException e) {
				log.warn("Invalid inbox.sources JSON, ignoring: {}", e.getMessage());
				return List.of();
			}
		}
		// Deployment default from application.yaml.
		return yamlDefaults();
	}

	/**
	 * {@code tourgaze.inbox.sources} from application.yaml (deployment default).
	 */
	private List<InboxSource> yamlDefaults() {
		return appConfig.getInbox().getSources().stream()
				.filter(s -> s != null && s.getPath() != null && !s.getPath().isBlank())
				.map(s -> new InboxSource(s.getLabel(), s.getPath(), s.isKeepOriginal()))
				.toList();
	}

	private Path toPath(String s) {
		try {
			return Path.of(s);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean copyIfNew(Path source, Set<String> parkedHashes, boolean keepOriginal, String label) {
		try {
			byte[] data = Files.readAllBytes(source);
			String sha = inboxService.hashOf(data);
			String filename = source.getFileName().toString();
			// Dismissed (ignored/processed) or already staged → leave it alone.
			if (parkedHashes.contains(sha) || inboxService.isAlreadyStaged(sha))
				return false;
			var importedId = inboxService.importedActivityId(sha);
			if (importedId.isPresent()) {
				// Already in the repository — don't re-stage. Record it so the inbox
				// "Already imported" filter can surface it: silent skipping was opaque
				// ("is the watcher even working?").
				inboxService.recordSkipped(filename, label, importedId.get());
				return false;
			}
			// New file → copy into the working inbox. The source is a device/cloud
			// folder we must not mutate here.
			Path target = storage.inboxDir().resolve(filename);
			Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
			inboxService.recordOrigin(filename, label);
			// Delete-from-device: remember the original so IMPORT removes it after the
			// store/ copy succeeds — never here at scan time. That way a not-yet-
			// imported file always stays recoverable from the device.
			if (!keepOriginal)
				inboxService.recordSource(filename, source);
			return true;
		} catch (IOException e) {
			log.warn("Inbox-folder scan: could not copy {}: {}", source, e.getMessage());
			return false;
		}
	}

	private static boolean isSupported(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		int dot = n.lastIndexOf('.');
		return dot >= 0 && EXTS.contains(n.substring(dot + 1));
	}

	/**
	 * "Wait until written": skip a file another process is still copying/writing
	 * (a Garmin transfer or Google-Drive sync in flight), so we never hash+import
	 * a half-written ride. On Windows the in-progress destination is held with an
	 * exclusive/non-shared handle, so opening it for write fails — we just retry
	 * on the next scan once the writer is done. Opening for write changes nothing
	 * (we write no bytes), so it's still copy-never-move. (matros isStable
	 * pattern.)
	 */
	private static boolean isFullyWritten(Path p) {
		try (FileChannel ignored = FileChannel.open(p, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
			return Files.size(p) > 0;
		} catch (IOException e) {
			log.debug("Skipping {} — still being written or locked: {}", p.getFileName(), e.getMessage());
			return false;
		}
	}
}
