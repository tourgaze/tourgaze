/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.entity.Sport;

@Repository
public interface SportRepository extends JpaRepository<Sport, String> {
	List<Sport> findByOrderByOrdinalAscNameAsc();

	List<Sport> findByEnabledTrueOrderByOrdinalAscNameAsc();

	Optional<Sport> findByKeyIgnoreCase(String key);
}
