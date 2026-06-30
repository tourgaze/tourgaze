/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

/**
 * Periodically forces H2 to sync committed changes from its in-memory write
 * buffer down to the physical {@code .mv.db} file with {@code CHECKPOINT SYNC}.
 *
 * <p>Why: a normal Ctrl-C is already safe — Spring's shutdown hook runs the
 * graceful shutdown and H2 closes the store cleanly. This guards the <em>hard</em>
 * kill instead (taskkill /F, force-closing the console window, power loss), where
 * no shutdown hook runs. H2's own write-delay only auto-flushes about once a
 * second, so without this a crash could drop the last few seconds of just-saved
 * metadata. A periodic sync bounds that worst-case loss to the interval and keeps
 * the file from growing unbounded by letting H2 consolidate/reuse old pages.
 *
 * <p>H2-only ({@code @Profile("!postgres")}): {@code CHECKPOINT SYNC} is H2
 * syntax, and the Postgres deployment leaves durability to the server. Pairs with
 * {@link DbBackupService} (which takes zip snapshots, not a flush).
 */
@Service
@Profile("!postgres")
public class H2CheckpointService {

	private static final Logger log = LoggerFactory.getLogger(H2CheckpointService.class);

	private final JdbcTemplate jdbc;

	public H2CheckpointService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Flush + fsync on a fixed interval (default 60 s; override with the prop). */
	@Scheduled(fixedDelayString = "${tourgaze.db.checkpoint-ms:60000}",
			initialDelayString = "${tourgaze.db.checkpoint-ms:60000}")
	public void checkpoint() {
		flush("scheduled");
	}

	/** One last durable flush before the context closes — belt-and-suspenders on
	 *  top of H2's clean shutdown close. */
	@PreDestroy
	public void flushOnShutdown() {
		flush("shutdown");
	}

	private void flush(String reason) {
		try {
			jdbc.execute("CHECKPOINT SYNC");
			log.debug("[DB] checkpoint ({})", reason);
		} catch (Exception e) {
			// Never let a maintenance flush take down a scheduler tick or shutdown.
			log.warn("[DB] checkpoint failed ({}): {}", reason, e.getMessage());
		}
	}
}
