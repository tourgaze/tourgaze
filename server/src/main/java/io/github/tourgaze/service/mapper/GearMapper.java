/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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
