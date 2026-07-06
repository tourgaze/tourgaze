/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import io.github.tourgaze.dto.RouteCandidate;
import io.github.tourgaze.entity.Activity;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, String> {
	// @EntityGraph eager-fetches tags in one join instead of N+1 lazy
	// round-trips. The list endpoint accesses .getTags() on every row to
	// build the summary DTO — and the mapper also reads gear + user, which
	// would otherwise lazy-init one proxy query per distinct gear/user.
	@EntityGraph(attributePaths = { "tags", "gear", "user" })
	List<Activity> findAllByOrderByStartTimeDesc();

	/** Used for dedup — re-uploading the same .fit/.gpx is a no-op. */
	Optional<Activity> findBySourceHash(String sourceHash);

	/**
	 * Full load with gear + user materialized, for the gear-reclassify sweep: it
	 * classifies OUTSIDE any transaction (Overpass calls + politeness sleeps must
	 * not pin a connection), so every relation it reads has to be fetched up
	 * front — a detached lazy proxy would throw on first access.
	 */
	@EntityGraph(attributePaths = { "gear", "user" })
	@Query("select a from Activity a")
	List<Activity> findAllWithGearAndUser();

	/**
	 * Scalar projection of every ride except {@code excludeId}, for route-
	 * similarity scoring. Pulls only the columns the compare picker needs (incl.
	 * the precomputed {@code routeGeocells} fingerprint) — no managed entities,
	 * no lazy tag collections — so /similar doesn't do a full findAll + N+1.
	 */
	@Query("select new io.github.tourgaze.dto.RouteCandidate("
			+ "a.id, a.name, a.activityType, a.startTime, a.distanceKm, a.durationS, a.startLocation, a.routeGeocells, "
			+ "coalesce(u.displayName, u.username)) "
			+ "from Activity a left join a.user u where a.id <> :excludeId")
	List<RouteCandidate> findRouteCandidates(@Param("excludeId") String excludeId);

	/** Ids of rides sharing at least one of the given tags — one query, no N+1. */
	@Query("select distinct a.id from Activity a join a.tags t where t.id in :tagIds")
	Set<String> findIdsSharingAnyTag(@Param("tagIds") Collection<String> tagIds);

	/**
	 * Distinct activities linked to any of the given tags — drives the
	 * tag-delete impact analysis (count of rides that would lose a tag link).
	 */
	@org.springframework.data.jpa.repository.Query("select count(distinct a.id) from Activity a join a.tags t where t.id in :tagIds")
	long countDistinctByTagIdIn(
			@org.springframework.data.repository.query.Param("tagIds") java.util.Collection<String> tagIds);

	/**
	 * Rider display name (or username) for a ride, by its on-disk filename — for
	 * photo authorship.
	 */
	@org.springframework.data.jpa.repository.Query("select coalesce(u.displayName, u.username) from Activity a join a.user u where a.sourceFilename = :sf")
	Optional<String> findRiderName(@org.springframework.data.repository.query.Param("sf") String sourceFilename);

	/**
	 * Bbox query used by PredictionService to find rides with similar start
	 * point. EntityGraph fetches tags eagerly so the per-row tag-frequency
	 * loop in the service doesn't trigger N+1.
	 */
	@EntityGraph(attributePaths = "tags")
	List<Activity> findByStartLatBetweenAndStartLonBetween(
			double minLat, double maxLat, double minLon, double maxLon);
}
