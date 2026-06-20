/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.entity.Tag;

@Repository
public interface TagRepository extends JpaRepository<Tag, String> {
	List<Tag> findAllByOrderByNameAsc();

	/**
	 * Root-level (no-parent) tags matching this name, case-insensitive — for
	 * find-or-create of accepted region/country proposals at import.
	 */
	List<Tag> findByParentIsNullAndNameIgnoreCase(String name);
}
