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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.github.tourgaze.store.StorageService;

/**
 * Periodic H2 snapshots via the online {@code BACKUP TO '<zip>'} command — a
 * startup snapshot (the moment the app comes up, before the user changes
 * anything) and a nightly one. The folder is rotated to the newest
 * {@link #MAX_BACKUPS} zips total (across both kinds), so the cloud-synced
 * directory stays small. Zips land in {@link StorageService#dbBackupDir()}
 * (under repositoryDir), so they sync to the cloud for off-machine recovery
 * while the live DB file stays local. (matros H2BackupService pattern.)
 */
@Service
public class DbBackupService {

	private static final Logger log = LoggerFactory.getLogger(DbBackupService.class);

	/** Newest snapshots to keep in the (cloud-synced) db-backup folder, total. */
	private static final int MAX_BACKUPS = 5;
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private final JdbcTemplate jdbc;
	private final StorageService storage;

	@Value("${app.backup.on-startup:true}")
	private boolean backupOnStartup;

	public DbBackupService(JdbcTemplate jdbc, StorageService storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	/** Snapshot once the app is up. Async so it never delays readiness. */
	@Async
	@EventListener(ApplicationReadyEvent.class)
	public void onStartup() {
		if (!backupOnStartup)
			return;
		log.info("[Backup] Creating startup DB snapshot…");
		if (createBackup("startup"))
			cleanupOldBackups();
	}

	@Scheduled(cron = "${tourgaze.backup.daily-cron:0 0 3 * * ?}")
	public void performDailyBackup() {
		log.info("[Backup] Creating nightly DB snapshot…");
		if (createBackup("daily"))
			cleanupOldBackups();
	}

	private boolean createBackup(String type) {
		try {
			Path dir = storage.dbBackupDir();
			Files.createDirectories(dir);
			Path target = dir.resolve("tourgaze-db-" + type + "-" + LocalDateTime.now().format(TS) + ".zip");
			// H2 online backup — forward slashes so the path is valid inside the SQL
			// string literal on Windows too.
			jdbc.execute("BACKUP TO '" + target.toAbsolutePath().toString().replace("\\", "/") + "'");
			log.info("[Backup] DB snapshot written: {}", target.getFileName());
			return true;
		} catch (Exception e) {
			log.error("[Backup] DB backup failed (disk full or DB locked?): {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Keep only the newest {@link #MAX_BACKUPS} snapshots in the folder (startup
	 * + daily combined); delete the rest so the cloud-synced folder stays small.
	 */
	private void cleanupOldBackups() {
		try (Stream<Path> files = Files.list(storage.dbBackupDir())) {
			List<Path> backups = files
					.filter(p -> p.getFileName().toString().startsWith("tourgaze-db-"))
					.filter(p -> p.toString().endsWith(".zip"))
					.sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
					.toList();
			for (int i = 0; i < backups.size() - MAX_BACKUPS; i++) {
				Files.deleteIfExists(backups.get(i));
				log.info("[Backup] Rotated old snapshot {}", backups.get(i).getFileName());
			}
		} catch (IOException e) {
			log.warn("[Backup] Cleanup failed: {}", e.getMessage());
		}
	}
}
