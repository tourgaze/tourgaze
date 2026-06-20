/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import io.github.tourgaze.dto.GearDto;
import io.github.tourgaze.entity.Gear;

/**
 * Gear entity → {@link GearDto}. Flattens the optional owning user to its id;
 * the rest is a straight name match.
 */
@Mapper(componentModel = "spring")
public interface GearMapper {
	@Mapping(target = "userId", source = "user.id")
	GearDto toDto(Gear g);
}
