/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

import io.github.tourgaze.exception.DatabaseTooNewException;

/**
 * Renders a {@link DatabaseTooNewException} as a tidy "APPLICATION FAILED TO
 * START" block (description + action) instead of a Spring stacktrace, so a
 * non-technical user reading the console window understands what to do.
 *
 * <p>
 * Registered via {@code META-INF/spring.factories}.
 */
public class DatabaseTooNewFailureAnalyzer extends AbstractFailureAnalyzer<DatabaseTooNewException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, DatabaseTooNewException cause) {
		String description = "Your TourGaze data was last opened by a NEWER version of the app.\n"
				+ "    Database schema version : v" + cause.getDbSchemaVersion() + "\n"
				+ "    This application supports: v" + cause.getAppSchemaVersion() + " and older\n\n"
				+ "    Starting this older version could corrupt your rides and settings, so it was stopped.";
		String action = "Start TourGaze again using the newer version you installed most recently.\n"
				+ "    Your data has NOT been changed. If you must use this older version, restore the\n"
				+ "    matching database backup from your data folder's db-backup/ directory first.";
		return new FailureAnalysis(description, action, cause);
	}
}
