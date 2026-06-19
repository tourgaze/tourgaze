/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.entity.GeoFeature;

@Repository
public interface GeoFeatureRepository extends JpaRepository<GeoFeature, Long> {

	/** All cached peaks/passes whose geohash cell is in the given set. */
	List<GeoFeature> findByGeocellIn(Collection<String> geocells);
}
