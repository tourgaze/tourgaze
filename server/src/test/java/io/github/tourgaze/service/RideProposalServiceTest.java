/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.tourgaze.enums.ActivityType;

class RideProposalServiceTest {

	// proposeType doesn't touch the delegate, so a null predictor is fine here.
	private final RideProposalService svc = new RideProposalService(null);

	@Test
	void deviceSportWins() {
		assertThat(svc.proposeType("running", 30.0, 12.0)).isEqualTo(ActivityType.RUNNING);
	}

	@Test
	void infersFromPaceWhenNoDeviceSport() {
		assertThat(svc.proposeType(null, 25.0, 60.0)).isEqualTo(ActivityType.CYCLING);
		assertThat(svc.proposeType(null, 9.0, 8.0)).isEqualTo(ActivityType.RUNNING); // short + slow = run
		assertThat(svc.proposeType(null, 4.0, 6.0)).isEqualTo(ActivityType.HIKING); // walking pace = wandern
	}

	@Test
	void longRideInRunBandIsCycling() {
		// 11 km/h over 45 km is a leisure ride, not a run.
		assertThat(svc.proposeType(null, 11.0, 45.0)).isEqualTo(ActivityType.CYCLING);
	}

	@Test
	void genericFallsThroughToPaceInference() {
		assertThat(svc.proposeType("generic", 25.0, 50.0)).isEqualTo(ActivityType.CYCLING);
	}

	@Test
	void unknownPaceDefaultsToCycling() {
		assertThat(svc.proposeType(null, null, null)).isEqualTo(ActivityType.CYCLING);
	}
}
