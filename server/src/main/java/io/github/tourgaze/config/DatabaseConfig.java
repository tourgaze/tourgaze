/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wraps Flyway's migration step with the {@link SchemaVersionGuard} downgrade
 * check. Defining a {@link FlywayMigrationStrategy} bean replaces Spring Boot's
 * default {@code flyway.migrate()} call, so the guard runs on every boot before
 * the schema is touched.
 */
@Configuration
public class DatabaseConfig {

	@Bean
	FlywayMigrationStrategy downgradeGuardedMigration() {
		return flyway -> {
			SchemaVersionGuard.verifyNotNewerThanApp(flyway.info());
			flyway.migrate();
		};
	}
}
