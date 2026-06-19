/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service.mapper;

import org.mapstruct.Mapper;

import io.github.tourgaze.dto.MapProviderDto;
import io.github.tourgaze.entity.MapProvider;

/**
 * MapProvider entity → {@link MapProviderDto}. Straight 1:1 name match
 * ({@code isDark()} → {@code dark}), no hints needed.
 */
@Mapper(componentModel = "spring")
public interface MapProviderMapper {
	MapProviderDto toDto(MapProvider p);
}
