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

import io.github.tourgaze.dto.TagDto;
import io.github.tourgaze.entity.Tag;

/**
 * Tag entity → {@link TagDto}. Flattens the optional parent to its id; the rest
 * is a straight name match.
 */
@Mapper(componentModel = "spring")
public interface TagMapper {
	@Mapping(target = "parentId", source = "parent.id")
	TagDto toDto(Tag t);
}
