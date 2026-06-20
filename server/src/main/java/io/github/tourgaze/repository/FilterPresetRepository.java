/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.entity.FilterPreset;

@Repository
public interface FilterPresetRepository extends JpaRepository<FilterPreset, String> {
	List<FilterPreset> findAllByOrderByNameAsc();

	/** Presets that group by any of the given tags — for tag-delete impact. */
	long countByGroupTagIdIn(java.util.Collection<String> groupTagIds);
}
