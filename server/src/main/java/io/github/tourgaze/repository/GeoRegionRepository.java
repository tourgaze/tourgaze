/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.entity.GeoRegion;

@Repository
public interface GeoRegionRepository extends JpaRepository<GeoRegion, String> {

	/** Which of the given cells have already been fetched. */
	List<GeoRegion> findByGeocellIn(Collection<String> geocells);
}
