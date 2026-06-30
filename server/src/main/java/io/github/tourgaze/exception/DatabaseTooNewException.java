/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.exception;

/**
 * Thrown at boot when the database was migrated by a <em>newer</em> build of
 * TourGaze than the one now starting (i.e. the schema history contains a
 * migration this jar doesn't ship). Running an old jar against a newer schema
 * risks silently corrupting data, so we refuse to start.
 *
 * <p>Reported cleanly by
 * {@link io.github.tourgaze.config.DatabaseTooNewFailureAnalyzer} and handled in
 * {@link io.github.tourgaze.TourgazeApplication#main(String[])}, which keeps the
 * console window open so the user can read the explanation.
 */
public class DatabaseTooNewException extends RuntimeException {

	private final String appSchemaVersion;
	private final String dbSchemaVersion;

	public DatabaseTooNewException(String appSchemaVersion, String dbSchemaVersion) {
		super("Database schema v" + dbSchemaVersion + " is newer than this application, which was built for schema v"
				+ appSchemaVersion + ". Refusing to start to avoid corrupting your data.");
		this.appSchemaVersion = appSchemaVersion;
		this.dbSchemaVersion = dbSchemaVersion;
	}

	public String getAppSchemaVersion() {
		return appSchemaVersion;
	}

	public String getDbSchemaVersion() {
		return dbSchemaVersion;
	}
}
