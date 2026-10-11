/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Port resolution only — needs no SERVER_PORT env / server.port property set in the test JVM. */
class SingleInstanceGuardTest {

	private static final String[] NO_ARGS = {};

	@TempDir
	Path home;

	@Test
	void defaultsTo8085WithoutUserConfig() {
		assertThat(SingleInstanceGuard.resolvePort(NO_ARGS, home.resolve("missing.yaml"))).isEqualTo(8085);
	}

	@Test
	void readsThePortTheFailureDialogTellsPeopleToSet() throws Exception {
		Path config = Files.writeString(home.resolve("tourgaze.yaml"), """
				#tourgaze:
				#  data-dir: /somewhere
				server:
				  port: 18085
				""");
		assertThat(SingleInstanceGuard.resolvePort(NO_ARGS, config)).isEqualTo(18085);
	}

	@Test
	void readsTheFlatKeyToo() throws Exception {
		Path config = Files.writeString(home.resolve("tourgaze.yaml"), "server.port: 18090\n");
		assertThat(SingleInstanceGuard.resolvePort(NO_ARGS, config)).isEqualTo(18090);
	}

	@Test
	void commandLineBeatsUserConfig() throws Exception {
		Path config = Files.writeString(home.resolve("tourgaze.yaml"), "server:\n  port: 18085\n");
		assertThat(SingleInstanceGuard.resolvePort(new String[] { "--server.port=9999" }, config)).isEqualTo(9999);
	}

	@Test
	void untouchedOrBrokenConfigFallsBackToTheDefault() throws Exception {
		Path commented = Files.writeString(home.resolve("a.yaml"), "#server:\n#  port: 1\n");
		Path broken = Files.writeString(home.resolve("b.yaml"), "server: [unclosed\n");
		assertThat(SingleInstanceGuard.resolvePort(NO_ARGS, commented)).isEqualTo(8085);
		assertThat(SingleInstanceGuard.resolvePort(NO_ARGS, broken)).isEqualTo(8085);
	}
}
