/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service.mapper;

import java.util.List;
import java.util.Set;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import io.github.tourgaze.dto.ActivitySummaryDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.enums.ActivityType;
import io.github.tourgaze.parser.SourceFormat;

/**
 * Activity entity → {@link ActivitySummaryDto} (the list/detail wire shape).
 * MapStruct generates the implementation; the {@code @Mapping}s cover the few
 * fields that aren't a straight name match — the embedded weather, the gear and
 * tag relations, and the String→enum coercions.
 */
@Mapper(componentModel = "spring")
public interface ActivitySummaryMapper {

	@Mapping(target = "activityType", source = "activityType", qualifiedByName = "toActivityType")
	@Mapping(target = "sourceFormat", source = "sourceFormat", qualifiedByName = "toSourceFormat")
	@Mapping(target = "weatherTempC", source = "weather.tempC")
	@Mapping(target = "weatherHumidityPct", source = "weather.humidityPct")
	@Mapping(target = "weatherWindKph", source = "weather.windKph")
	@Mapping(target = "weatherCondition", source = "weather.condition")
	@Mapping(target = "tagIds", source = "tags", qualifiedByName = "toTagIds")
	@Mapping(target = "gearId", source = "gear.id")
	@Mapping(target = "gearName", source = "gear.name")
	ActivitySummaryDto toDto(Activity a);

	@Named("toActivityType")
	static ActivityType toActivityType(String s) {
		return ActivityType.from(s);
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
