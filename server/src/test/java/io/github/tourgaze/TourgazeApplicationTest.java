/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TourgazeApplicationTest {

	private String saved;

	@BeforeEach
	void save() {
		saved = System.getProperty("java.awt.headless");
	}

	@AfterEach
	void restore() {
		if (saved == null)
			System.clearProperty("java.awt.headless");
		else
			System.setProperty("java.awt.headless", saved);
	}

	@Test
	void desktopBundleListensOnThisComputerOnly() {
		System.setProperty("java.awt.headless", "false"); // what the jpackage launcher passes
		assertThat(TourgazeApplication.desktopDefaults()).containsEntry("server.address", "127.0.0.1");
	}

	@Test
	void headlessJarAndDockerKeepListeningOnTheNetwork() {
		System.clearProperty("java.awt.headless");
		assertThat(TourgazeApplication.desktopDefaults()).isEmpty();
		System.setProperty("java.awt.headless", "true");
		assertThat(TourgazeApplication.desktopDefaults()).isEmpty();
	}
}
