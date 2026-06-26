/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Scaffolds the optional user config file at
 * {@code ~/.config/tourgaze/tourgaze.yaml} on first run so there's an obvious,
 * documented place to relocate the data directory without juggling env vars.
 *
 * <p>
 * The template is fully commented out — an untouched file changes nothing. It's
 * read (if present) at startup via {@code spring.config.import} in
 * {@code application.yaml}; this only writes the template, so edits take effect
 * on the next launch. Best-effort: a failure to write is logged, never fatal.
 *
 * <p>
 * Precedence stays: env vars / {@code -D} flags &gt; this file &gt; defaults.
 */
@Component
public class HomeConfigScaffold {

	private static final Logger log = LoggerFactory.getLogger(HomeConfigScaffold.class);

	private static final String TEMPLATE = """
			# TourGaze user configuration.
			#
			# Optional overrides for the built-in defaults. Everything here is commented
			# out, so an untouched file changes nothing. Delete the file to forget it.
			#
			# Precedence (highest wins):
			#   1. TOURGAZE_DATA_DIR env var  /  -Dtourgaze.* flags
			#   2. this file  (~/.config/tourgaze/tourgaze.yaml)
			#   3. built-in defaults
			#
			# Changes take effect on the next start.
			#
			#tourgaze:
			#  # Where TourGaze keeps everything: the H2 database, caches, logs and (by
			#  # default) the repository. Default: <your home>/.tourgaze
			#  data-dir: /path/to/tourgaze-data
			#
			#  # The precious, cloud-syncable library: ride files, photos and metadata
			#  # sidecars. Point it at a synced folder (Dropbox, Google Drive, etc.) to
			#  # back it up off-machine. Default: <data-dir>/repository
			#  repository-dir: /path/to/cloud-synced/tourgaze
			""";

	@PostConstruct
	void scaffold() {
		Path file = Path.of(System.getProperty("user.home"), ".config", "tourgaze", "tourgaze.yaml");
		try {
			if (Files.exists(file))
				return;
			Files.createDirectories(file.getParent());
			Files.writeString(file, TEMPLATE);
			log.info("[Config] Wrote a starter config at {} (commented; edit to relocate the data dir).", file);
		} catch (IOException e) {
			// Read-only home, odd permissions, etc. — never fatal; defaults still apply.
			log.debug("[Config] Could not scaffold {}: {}", file, e.getMessage());
		}
	}
}
