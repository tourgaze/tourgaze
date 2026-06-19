/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SourceFormatTest {

	@Test
	void parsesKnownExtensionsCaseInsensitively() {
		assertThat(SourceFormat.from("FIT")).isEqualTo(SourceFormat.FIT);
		assertThat(SourceFormat.from("gpx")).isEqualTo(SourceFormat.GPX);
		assertThat(SourceFormat.from("Kmz")).isEqualTo(SourceFormat.KMZ);
	}

	@Test
	void unknownOrNullIsNull() {
		assertThat(SourceFormat.from("rar")).isNull();
		assertThat(SourceFormat.from(null)).isNull();
	}

	@Test
	void extensionsListsAllWireValues() {
		assertThat(SourceFormat.extensions()).containsExactlyInAnyOrder("fit", "gpx", "tcx", "kmz", "kml");
	}
}
