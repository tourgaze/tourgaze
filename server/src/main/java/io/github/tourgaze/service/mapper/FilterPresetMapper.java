/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
