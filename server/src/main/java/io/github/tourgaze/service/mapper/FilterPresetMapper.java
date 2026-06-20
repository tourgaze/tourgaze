/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service.mapper;

import org.mapstruct.Mapper;

import io.github.tourgaze.dto.FilterPresetDto;
import io.github.tourgaze.entity.FilterPreset;

/**
 * FilterPreset entity → {@link FilterPresetDto}. Straight 1:1 name match.
 */
@Mapper(componentModel = "spring")
public interface FilterPresetMapper {
	FilterPresetDto toDto(FilterPreset p);
}
