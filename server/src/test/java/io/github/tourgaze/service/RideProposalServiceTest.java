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

import io.github.tourgaze.enums.ActivityType;

class RideProposalServiceTest {

	// proposeType doesn't touch the repository, so a null repo is fine here.
	private final RideProposalService svc = new RideProposalService(null);

	@Test
	void deviceSportWins() {
		assertThat(svc.proposeType("running", 30.0)).isEqualTo(ActivityType.RUNNING);
	}

	@Test
	void infersFromPaceWhenNoDeviceSport() {
		assertThat(svc.proposeType(null, 25.0)).isEqualTo(ActivityType.CYCLING);
		assertThat(svc.proposeType(null, 9.0)).isEqualTo(ActivityType.RUNNING);
		assertThat(svc.proposeType(null, 4.0)).isEqualTo(ActivityType.HIKING);
	}

	@Test
	void genericFallsThroughToPaceInference() {
		assertThat(svc.proposeType("generic", 25.0)).isEqualTo(ActivityType.CYCLING);
	}

	@Test
	void unknownPaceDefaultsToCycling() {
		assertThat(svc.proposeType(null, null)).isEqualTo(ActivityType.CYCLING);
	}
}
