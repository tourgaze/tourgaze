/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the whole application context against an in-memory H2. This is the
 * guard for DB-model soundness: Flyway applies the V1 baseline and Hibernate
 * runs ddl-auto=validate, so the context only starts if every entity (columns,
 * types, {@code @Version}, {@code updated_at}, the embeddable Weather, …) maps
 * cleanly onto the migrated schema. Any drift fails this test at boot.
 */
@SpringBootTest
class SchemaValidationTest {

	@Test
	void contextLoadsAndSchemaValidates() {
		// Success == the context started: Flyway migrated + Hibernate validated.
	}
}
