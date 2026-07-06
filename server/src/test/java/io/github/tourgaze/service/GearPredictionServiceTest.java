/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.service.GearPredictionService.Category;
import io.github.tourgaze.service.GearPredictionService.Prediction;

/**
 * Verifies the offline classification precedence and the history (speed/watt)
 * rescue — the parts with real decision logic. The OSM woods path is exercised
 * separately at runtime; here every ride resolves on the cheap signals.
 */
class GearPredictionServiceTest {

	private final ActivityRepository activityRepo = mock(ActivityRepository.class);
	private final GearRepository gearRepo = mock(GearRepository.class);
	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	// forest / storage / trackParser are only used on the OSM woods path, which
	// none of these cases hit — so null is fine and avoids mocking concrete
	// types. Null txManager → runInTx executes the writes directly.
	private final GearPredictionService svc = new GearPredictionService(
			activityRepo, gearRepo, null, null, null, events, null);

	private final Gear mtb = gear("g-mtb", "Hibike MTB", "mtb");
	private final Gear race = gear("g-race", "Lapierre racebike", "race");

	@Test
	void precedence_humanSetBeatsFast_andWrongGearIsCleared() {
		when(gearRepo.findAll()).thenReturn(List.of(mtb, race));

		Activity fast = ride("a-fast", 30.0, 40.0, 200.0, null, null); // fast → race
		Activity mtn = ride("a-mtn", 15.0, 25.0, 300.0, "mountain", null); // sub-sport → mtb
		Activity human = ride("a-human", 30.0, 40.0, 200.0, null, mtb); // fast, but…
		human.getAttributes().put("gearSource", "user"); // …human-set → locked
		Activity slow = ride("a-slow", 16.0, 20.0, 50.0, null, race); // slow+flat, wrong gear

		when(activityRepo.findAllWithGearAndUser()).thenReturn(List.of(fast, mtn, human, slow));
		when(activityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		var byId = predict(true);

		assertThat(byId.get("a-fast").category()).isEqualTo(Category.RACE);
		assertThat(byId.get("a-fast").newGear()).isEqualTo("Lapierre racebike");
		assertThat(fast.getGear()).isSameAs(race);

		assertThat(byId.get("a-mtn").category()).isEqualTo(Category.MTB);
		assertThat(mtn.getGear()).isSameAs(mtb);

		// Human-set wins over the "fast → racebike" rule and is never touched.
		assertThat(byId.get("a-human").changed()).isFalse();
		assertThat(byId.get("a-human").newGear()).isEqualTo("Hibike MTB");
		assertThat(human.getGear()).isSameAs(mtb);

		// Slow + flat + not human-set → cleared (default no gear).
		assertThat(byId.get("a-slow").category()).isEqualTo(Category.NONE);
		assertThat(byId.get("a-slow").newGear()).isNull();
		assertThat(byId.get("a-slow").changed()).isTrue();
		assertThat(slow.getGear()).isNull();
	}

	@Test
	void historyRescue_matchesAmbiguousRideBySpeedAndWatt() {
		when(gearRepo.findAll()).thenReturn(List.of(mtb, race));

		// Three confident racebike rides (fast, ~250 W) and three MTB rides
		// (sub-sport mountain, ~180 W) — enough to build both profiles.
		Activity r1 = watt(ride("r1", 30.0, 50.0, 200.0, null, null), 250);
		Activity r2 = watt(ride("r2", 31.0, 55.0, 210.0, null, null), 252);
		Activity r3 = watt(ride("r3", 29.0, 48.0, 190.0, null, null), 248);
		Activity m1 = watt(ride("m1", 14.0, 20.0, 400.0, "mountain", null), 180);
		Activity m2 = watt(ride("m2", 13.0, 22.0, 420.0, "mountain", null), 182);
		Activity m3 = watt(ride("m3", 15.0, 19.0, 390.0, "mountain", null), 178);

		// The unknown: mid pace (not fast, not steep+slow) but power like a racebike.
		Activity x = watt(ride("a-x", 22.0, 40.0, 120.0, null, null), 248);

		when(activityRepo.findAllWithGearAndUser()).thenReturn(List.of(r1, r2, r3, m1, m2, m3, x));
		when(activityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Prediction px = predict(false).get("a-x");
		assertThat(px.category()).isEqualTo(Category.RACE);
		assertThat(px.reason()).contains("speed/watt");
	}

	@Test
	void woodsSimilarity_forestShareDecidesWhenSpeedIsTooCloseToCall() {
		when(gearRepo.findAll()).thenReturn(List.of(mtb, race));

		// MTB history: sub-sport mountain, moderate speeds, mostly in the woods.
		Activity m1 = forestFrac(ride("m1", 18.0, 25.0, 300.0, "mountain", null), 0.80);
		Activity m2 = forestFrac(ride("m2", 20.0, 22.0, 280.0, "mountain", null), 0.75);
		Activity m3 = forestFrac(ride("m3", 22.0, 28.0, 320.0, "mountain", null), 0.85);
		// Race history: fast, in the open.
		Activity r1 = forestFrac(ride("r1", 30.0, 60.0, 200.0, null, null), 0.00);
		Activity r2 = forestFrac(ride("r2", 31.0, 65.0, 210.0, null, null), 0.05);
		Activity r3 = forestFrac(ride("r3", 29.0, 55.0, 190.0, null, null), 0.10);

		// The unknown: 24 km/h sits between both speed profiles (too close to
		// call on speed alone — the margin rule would refuse), but 75% of the
		// track ran through forest, like the MTB history.
		Activity x = forestFrac(ride("a-x", 24.0, 30.0, 100.0, null, null), 0.75);

		when(activityRepo.findAllWithGearAndUser()).thenReturn(List.of(m1, m2, m3, r1, r2, r3, x));
		when(activityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Prediction px = predict(false).get("a-x");
		assertThat(px.category()).isEqualTo(Category.MTB);
		assertThat(px.reason()).contains("woods");
	}

	@Test
	void steepSlow_reusesCachedForestShare_withoutAskingOverpass() {
		when(gearRepo.findAll()).thenReturn(List.of(mtb, race));

		// Steep + slow (ambiguous) — but the forest share was measured on an
		// earlier sweep and cached on the ride. The service must classify from
		// the cache; the ForestClient is null here, so any Overpass attempt
		// would NPE and fail this test.
		Activity cached = forestFrac(ride("a-cached", 12.0, 20.0, 400.0, null, null), 0.60);

		when(activityRepo.findAllWithGearAndUser()).thenReturn(List.of(cached));
		when(activityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

		Prediction p = predict(false).get("a-cached");
		assertThat(p.category()).isEqualTo(Category.MTB);
		assertThat(p.reason()).contains("cached");
	}

	// ── helpers ───────────────────────────────────────────────────────────────

	private java.util.Map<String, Prediction> predict(boolean apply) {
		return svc.reclassify(apply).predictions().stream()
				.collect(Collectors.toMap(Prediction::activityId, Function.identity()));
	}

	private static Gear gear(String id, String name, String type) {
		Gear g = new Gear();
		g.setId(id);
		g.setName(name);
		g.setType(type);
		return g;
	}

	private static Activity ride(String id, Double avg, Double dist, Double gain, String subSport, Gear gear) {
		Activity a = new Activity();
		a.setId(id);
		a.setActivityType("cycling");
		a.setAvgSpeedKmh(avg);
		a.setDistanceKm(dist);
		a.setElevationGainM(gain);
		a.setSubSport(subSport);
		a.setGear(gear);
		return a;
	}

	private static Activity watt(Activity a, int w) {
		a.setAvgPowerW(w);
		return a;
	}

	private static Activity forestFrac(Activity a, double frac) {
		a.getAttributes().put(GearPredictionService.FOREST_FRAC_ATTR, frac);
		return a;
	}
}
