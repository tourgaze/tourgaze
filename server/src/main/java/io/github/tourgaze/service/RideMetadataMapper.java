/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import io.github.tourgaze.dto.RideMetadataDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.entity.User;

/**
 * Builds the {@link RideMetadataDto} recovery shape from an {@link Activity}.
 */
@Component
public class RideMetadataMapper {

	public static final int SCHEMA_VERSION = 2; // v2: added free-form attributes (incl. events)

	/**
	 * Must be called within an open session — walks the lazy gear/user/tags
	 * relations.
	 */
	public RideMetadataDto toDto(Activity a, Instant exportedAt) {
		Gear g = a.getGear();
		User u = a.getUser();
		List<RideMetadataDto.TagRef> tags = a.getTags().stream()
				.map(t -> new RideMetadataDto.TagRef(t.getId(), t.getName()))
				.sorted(Comparator.comparing(t -> t.name() == null ? "" : t.name()))
				.toList();
		return new RideMetadataDto(
				SCHEMA_VERSION, exportedAt,
				a.getId(), a.getName(), a.getDescription(),
				a.getActivityType(),
				a.getStartTime(), a.getEndTime(), a.getDurationS(), a.getMovingTimeS(),
				a.getDistanceKm(), a.getElevationGainM(),
				a.getAvgHr(), a.getMaxHr(), a.getAvgSpeedKmh(), a.getMaxSpeedKmh(),
				a.getStartLat(), a.getStartLon(),
				a.getStartLocation(), a.getStartCountry(), a.getEndLocation(), a.getEndCountry(),
				a.getWeather().getTempC(), a.getWeather().getHumidityPct(), a.getWeather().getWindKph(),
				a.getWeather().getCondition(), a.getWeather().getFetchedAt(),
				a.getWeightKg(), a.getImportedAt(),
				new RideMetadataDto.SourceRef(a.getSourceFilename(), a.getOriginalFilename(),
						io.github.tourgaze.parser.SourceFormat.from(a.getSourceFormat()), a.getSourceHash()),
				g == null ? null : new RideMetadataDto.GearRef(g.getId(), g.getName(), g.getType()),
				u == null ? null : new RideMetadataDto.RiderRef(u.getId(), u.getUsername(), u.getDisplayName()),
				tags, a.getAttributes());
	}
}
