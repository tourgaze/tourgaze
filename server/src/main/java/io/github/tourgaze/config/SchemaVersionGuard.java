/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;

import io.github.tourgaze.exception.DatabaseTooNewException;

/**
 * Downgrade guard: refuse to start when the database is newer than this build.
 *
 * <p>
 * Flyway classifies a migration that is recorded in the DB's schema history
 * but absent from this jar's {@code db/migration} classpath as a
 * <em>future</em>
 * migration ({@link MigrationState#FUTURE_SUCCESS} /
 * {@link MigrationState#FUTURE_FAILED}). By default Flyway only <em>warns</em>
 * about those and migrates anyway, which would let an old jar run against a
 * schema it was never built for. We treat any future migration as fatal
 * instead.
 *
 * <p>
 * Run from the {@link DatabaseConfig} {@code FlywayMigrationStrategy} bean,
 * <em>before</em> {@code flyway.migrate()}, so the abort happens prior to any
 * schema or data change.
 */
final class SchemaVersionGuard {

	private SchemaVersionGuard() {
	}

	/**
	 * @throws DatabaseTooNewException
	 *             if the DB carries a schema version this build
	 *             does not know about.
	 */
	static void verifyNotNewerThanApp(MigrationInfoService info) {
		MigrationInfo[] all = info.all();

		String dbVersion = maxVersion(all, SchemaVersionGuard::isFuture);
		if (dbVersion == null)
			return; // no future migration — DB is at or below this build, all good.

		String appVersion = maxVersion(all, i -> !isFuture(i));
		throw new DatabaseTooNewException(appVersion == null ? "?" : appVersion, dbVersion);
	}

	/** Highest versioned migration matching {@code filter}, or null if none. */
	private static String maxVersion(MigrationInfo[] all, java.util.function.Predicate<MigrationInfo> filter) {
		return Arrays.stream(all)
				.filter(filter)
				.map(MigrationInfo::getVersion)
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder())
				.map(MigrationVersion::getVersion)
				.orElse(null);
	}

	private static boolean isFuture(MigrationInfo i) {
		MigrationState s = i.getState();
		return s == MigrationState.FUTURE_SUCCESS || s == MigrationState.FUTURE_FAILED;
	}
}
