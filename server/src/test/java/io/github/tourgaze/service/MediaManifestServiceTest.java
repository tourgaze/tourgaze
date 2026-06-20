/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaManifestServiceTest {

	@Test
	void publicPrefixesAreRecognised() {
		assertThat(MediaManifestService.isPublic("img_public_Foo.jpg")).isTrue();
		assertThat(MediaManifestService.isPublic("wiki_Bar.jpg")).isTrue(); // legacy
	}

	@Test
	void personalAndUploadsArePrivate() {
		assertThat(MediaManifestService.isPublic("img_personal_Foo.jpg")).isFalse();
		assertThat(MediaManifestService.isPublic("20240501_120000.jpg")).isFalse();
	}
}
