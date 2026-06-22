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

import io.github.tourgaze.entity.EventType;

@Repository
public interface EventTypeRepository extends JpaRepository<EventType, String> {
	List<EventType> findByOrderByOrdinalAscNameAsc();

	List<EventType> findByEnabledTrueOrderByOrdinalAscNameAsc();

	Optional<EventType> findByKeyIgnoreCase(String key);
}
