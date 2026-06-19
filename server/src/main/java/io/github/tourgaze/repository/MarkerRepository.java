/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.entity.Marker;

@Repository
public interface MarkerRepository extends JpaRepository<Marker, String> {

	/** Markers tied to one ride. */
	List<Marker> findByActivityId(String activityId);

	/** General markers (not tied to any ride) — shown on every map. */
	List<Marker> findByActivityIdIsNull();
}
