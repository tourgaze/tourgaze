/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service.mapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.ActivitySummaryDto;
import io.github.tourgaze.dto.RideEventDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.parser.SourceFormat;

/**
 * Activity entity → {@link ActivitySummaryDto} (the list/detail wire shape).
 * MapStruct generates the implementation; the {@code @Mapping}s cover the few
 * fields that aren't a straight name match — the embedded weather, the gear and
 * tag relations, and the String→enum coercions.
 */
@Mapper(componentModel = "spring")
public interface ActivitySummaryMapper {

	@Mapping(target = "sourceFormat", source = "sourceFormat", qualifiedByName = "toSourceFormat")
	@Mapping(target = "weatherTempC", source = "weather.tempC")
	@Mapping(target = "weatherHumidityPct", source = "weather.humidityPct")
	@Mapping(target = "weatherWindKph", source = "weather.windKph")
	@Mapping(target = "weatherCondition", source = "weather.condition")
	@Mapping(target = "tagIds", source = "tags", qualifiedByName = "toTagIds")
	@Mapping(target = "gearId", source = "gear.id")
	@Mapping(target = "gearName", source = "gear.name")
	@Mapping(target = "riderName", source = "user", qualifiedByName = "toRiderName")
	@Mapping(target = "events", source = "attributes", qualifiedByName = "toEvents")
	@Mapping(target = "sensors", source = "attributes", qualifiedByName = "toSensors")
	ActivitySummaryDto toDto(Activity a);

	@Named("toEvents")
	static List<RideEventDto> toEvents(Map<String, Object> attributes) {
		if (attributes == null)
			return List.of();
		Object raw = attributes.get(RideEventDto.ATTRIBUTES_KEY);
		if (raw == null)
			return List.of();
		return EVENT_JSON.convertValue(raw, new TypeReference<List<RideEventDto>>() {
		});
	}

	ObjectMapper EVENT_JSON = new ObjectMapper().findAndRegisterModules();

	@Named("toSensors")
	static List<io.github.tourgaze.enums.SensorType> toSensors(Map<String, Object> attributes) {
		if (attributes == null)
			return List.of();
		Object raw = attributes.get("sensors");
		if (raw == null)
			return List.of();
		// Stored as wire strings ("hr", "power", …); coerce, dropping unknowns.
		return EVENT_JSON.convertValue(raw, new TypeReference<List<String>>() {
		}).stream().map(io.github.tourgaze.enums.SensorType::fromWire).filter(java.util.Objects::nonNull).toList();
	}

	@Named("toRiderName")
	static String toRiderName(io.github.tourgaze.entity.User u) {
		if (u == null)
			return null;
		return u.getDisplayName() != null && !u.getDisplayName().isBlank()
				? u.getDisplayName()
				: u.getUsername();
	}

	@Named("toSourceFormat")
	static SourceFormat toSourceFormat(String s) {
		return SourceFormat.from(s);
	}

	@Named("toTagIds")
	static List<String> toTagIds(Set<Tag> tags) {
		return tags == null ? List.of() : tags.stream().map(Tag::getId).sorted().toList();
	}
}
