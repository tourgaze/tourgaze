/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service.mapper;

import org.mapstruct.Mapper;

import io.github.tourgaze.dto.MarkerDto;
import io.github.tourgaze.entity.Marker;

/**
 * Marker entity → {@link MarkerDto}. Every field is a straight name match, so
 * MapStruct needs no {@code @Mapping} hints.
 */
@Mapper(componentModel = "spring")
public interface MarkerMapper {
	MarkerDto toDto(Marker m);
}
