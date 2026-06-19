/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
