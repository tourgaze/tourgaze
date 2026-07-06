/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.tourgaze.service.GearPredictionService;
import io.github.tourgaze.service.GearPredictionService.Report;

/**
 * Repairs the bike-per-ride assignment that the MyTourbook import got wrong.
 *
 * <pre>
 *   POST /api/activities/reclassify-gear            → dry run: what it WOULD change
 *   POST /api/activities/reclassify-gear?apply=true → actually rewrite gear_id
 *   POST /api/activities/reclassify-gear?deep=true  → also measure the forest share
 *                                                     of every ride that has none yet
 *                                                     (one paced Overpass call each,
 *                                                     cached on the ride afterwards)
 * </pre>
 *
 * Rules (see {@link GearPredictionService}): forest → MTB, fast → racebike,
 * steep+slow disambiguated against OpenStreetMap, otherwise no gear. "Fast" and
 * "slow" are relative to each rider's own speed distribution. Measured forest
 * shares feed a third profile dimension, so a ride mostly in the woods matches
 * the bike your previous woods rides used.
 */
@RestController
@RequestMapping("/api/activities")
public class GearPredictionController {

	private final GearPredictionService prediction;

	public GearPredictionController(GearPredictionService prediction) {
		this.prediction = prediction;
	}

	@PostMapping("/reclassify-gear")
	public Report reclassify(@RequestParam(value = "apply", defaultValue = "false") boolean apply,
			@RequestParam(value = "deep", defaultValue = "false") boolean deep) {
		return prediction.reclassify(apply, deep);
	}
}
