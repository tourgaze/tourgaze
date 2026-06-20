/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
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
