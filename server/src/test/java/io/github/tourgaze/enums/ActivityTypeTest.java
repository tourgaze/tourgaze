/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActivityTypeTest {

	@Test
	void parsesWireValueCaseInsensitively() {
		assertThat(ActivityType.from("cycling")).isEqualTo(ActivityType.CYCLING);
		assertThat(ActivityType.from("RUNNING")).isEqualTo(ActivityType.RUNNING);
	}

	@Test
	void nullOrBlankIsNull() {
		assertThat(ActivityType.from(null)).isNull();
		assertThat(ActivityType.from("  ")).isNull();
	}

	@Test
	void unknownCollapsesToOther() {
		assertThat(ActivityType.from("kitesurfing")).isEqualTo(ActivityType.OTHER);
	}

	@Test
	void wireIsLowercase() {
		assertThat(ActivityType.CYCLING.wire()).isEqualTo("cycling");
	}
}
