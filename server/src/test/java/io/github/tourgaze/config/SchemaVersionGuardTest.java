/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import io.github.tourgaze.exception.DatabaseTooNewException;

/**
 * Exercises the downgrade guard against a real Flyway + H2 database. The
 * production migrations live in {@code classpath:db/migration}; the test "future"
 * migration ({@code db/future/V9999}) stands in for a newer app having already
 * upgraded the schema.
 */
class SchemaVersionGuardTest {

	private static final String PROD = "classpath:db/migration";
	private static final String FUTURE = "classpath:db/future";

	@Test
	void upToDateDatabaseStarts() {
		Flyway app = flyway("guard_up_to_date", PROD);
		app.migrate();

		assertThatCode(() -> SchemaVersionGuard.verifyNotNewerThanApp(app.info())).doesNotThrowAnyException();
	}

	@Test
	void databaseNewerThanAppIsRejected() {
		// A newer build migrated the DB to v9999 (prod + the extra future migration)...
		flyway("guard_too_new", PROD, FUTURE).migrate();

		// ...then this older build boots, seeing only the production migrations.
		Flyway app = flyway("guard_too_new", PROD);

		assertThatThrownBy(() -> SchemaVersionGuard.verifyNotNewerThanApp(app.info()))
				.isInstanceOfSatisfying(DatabaseTooNewException.class, ex -> {
					assertThat(ex.getDbSchemaVersion()).isEqualTo("9999");
					assertThat(ex.getAppSchemaVersion()).isNotEqualTo("9999"); // the real top-of-prod version
				});
	}

	/** Shared in-memory H2 (one named DB per test) in PostgreSQL compatibility mode. */
	private static Flyway flyway(String db, String... locations) {
		String url = "jdbc:h2:mem:" + db + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
		return Flyway.configure().dataSource(url, "sa", "").locations(locations).load();
	}
}
