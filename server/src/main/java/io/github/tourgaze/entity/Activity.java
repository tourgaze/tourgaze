/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.*;

import io.github.tourgaze.util.ShortId;

/**
 * Activity metadata stored in the DB.
 * Raw GPS track is NOT stored here — it lives on disk:
 * store/{sourceFilename} ← original file (FIT, GPX, …) named by hash + ext
 * cache/{id}.json ← lazy-built track-point JSON, keyed by short UUID
 * cache/{id}-chart.json ← LTTB-reduced chart points
 *
 * Primary key is a short, time-ordered UUID. {@code sourceHash} (SHA-256)
 * carries
 * the content fingerprint used to dedup re-uploads of the same file.
 */
@Entity
@Table(name = "activity", indexes = {
		@Index(name = "idx_activity_start", columnList = "start_time DESC"),
		@Index(name = "idx_activity_source_hash", columnList = "source_hash"),
		// FK lookups — list-by-rider, gear stats, gear filter.
		@Index(name = "idx_activity_user", columnList = "user_id"),
		@Index(name = "idx_activity_gear", columnList = "gear_id"),
})
public class Activity extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "gear_id")
	private Gear gear;

	/** SHA-256 hex of the original source file bytes. Unique → drives dedup. */
	@Column(name = "source_hash", nullable = false, unique = true, length = 64, updatable = false)
	private String sourceHash;

	/**
	 * Route fingerprint: the set of ~150 m geohash cells (precision 7) the track
	 * passes through, space-separated. Computed at import (lazily backfilled for
	 * old rides). Two rides' Jaccard overlap on these cell sets = "same route"
	 * similarity, used by the ghost-chase compare picker.
	 */
	@Column(name = "route_geocells", columnDefinition = "clob")
	private String routeGeocells;

	/**
	 * Filename inside the store/ directory (e.g. {@code <hash>.fit} /
	 * {@code <hash>.gpx}).
	 */
	@Column(name = "source_filename", nullable = false, length = 500)
	private String sourceFilename;

	/**
	 * Original filename as the user dropped it into the inbox — e.g.
	 * {@code 2026-06-13-tegernsee.fit}. Surface in the UI; not used for any
	 * file-system addressing (that's {@link #sourceFilename}).
	 */
	@Column(name = "original_filename", length = 500)
	private String originalFilename;

	/** Original-file format: "fit", "gpx", "tcx", etc. */
	@Column(name = "source_format", nullable = false, length = 20)
	private String sourceFormat;

	/**
	 * Rider body weight on the day of the ride (kg). Captured per-activity so
	 * the weight chart on the dashboard, calorie estimates, and power-to-weight
	 * ratios all use the as-of-ride value rather than the user's current
	 * profile weight. Pre-fills from {@code user.weightKg} at import time.
	 */
	@Column(name = "weight_kg")
	private Double weightKg;

	/**
	 * Reverse-geocoded start / end place names. Localised in the user's
	 * configured language (Settings → Language) via Nominatim. Pre-filled at
	 * import time by {@code PredictionService} and editable on the EditTour
	 * form. Surface in the Tours view for grouping / filtering by region.
	 */
	@Column(name = "start_location", length = 255)
	private String startLocation;

	@Column(name = "start_country", length = 2)
	private String startCountry;

	@Column(name = "end_location", length = 255)
	private String endLocation;

	@Column(name = "end_country", length = 2)
	private String endCountry;

	/** cycling, running, hiking, swimming, walking, other */
	@Column(name = "activity_type")
	private String activityType;

	private String name;
	private String description;

	@Column(name = "start_time")
	private Instant startTime;

	@Column(name = "end_time")
	private Instant endTime;

	@Column(name = "duration_s")
	private Integer durationS;

	@Column(name = "moving_time_s")
	private Integer movingTimeS;

	@Column(name = "distance_km")
	private Double distanceKm;

	@Column(name = "elevation_gain_m")
	private Double elevationGainM;

	@Column(name = "avg_hr")
	private Integer avgHr;

	@Column(name = "max_hr")
	private Integer maxHr;

	@Column(name = "avg_speed_kmh")
	private Double avgSpeedKmh;

	@Column(name = "max_speed_kmh")
	private Double maxSpeedKmh;

	@Column(name = "start_lat")
	private Double startLat;

	@Column(name = "start_lon")
	private Double startLon;

	/** Weather snapshot, grouped as an embeddable value object (same columns). */
	@Embedded
	private Weather weather = new Weather();

	@Column(name = "imported_at", nullable = false)
	private Instant importedAt;

	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(name = "activity_tag", joinColumns = @JoinColumn(name = "activity_id"), inverseJoinColumns = @JoinColumn(name = "tag_id"), indexes = {
			@Index(name = "idx_activity_tag_activity", columnList = "activity_id"),
			@Index(name = "idx_activity_tag_tag", columnList = "tag_id"),
	})
	private Set<Tag> tags = new HashSet<>();

	@PrePersist
	void ensureId() {
		if (id == null)
			id = ShortId.next();
		if (importedAt == null)
			importedAt = Instant.now();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public User getUser() {
		return user;
	}

	public void setUser(User user) {
		this.user = user;
	}

	public Gear getGear() {
		return gear;
	}

	public void setGear(Gear gear) {
		this.gear = gear;
	}

	public String getSourceHash() {
		return sourceHash;
	}

	public void setSourceHash(String sourceHash) {
		this.sourceHash = sourceHash;
	}

	public String getRouteGeocells() {
		return routeGeocells;
	}

	public void setRouteGeocells(String routeGeocells) {
		this.routeGeocells = routeGeocells;
	}

	public String getSourceFilename() {
		return sourceFilename;
	}

	public void setSourceFilename(String sourceFilename) {
		this.sourceFilename = sourceFilename;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public void setOriginalFilename(String originalFilename) {
		this.originalFilename = originalFilename;
	}

	public Double getWeightKg() {
		return weightKg;
	}

	public void setWeightKg(Double weightKg) {
		this.weightKg = weightKg;
	}

	public String getStartLocation() {
		return startLocation;
	}

	public void setStartLocation(String startLocation) {
		this.startLocation = startLocation;
	}

	public String getStartCountry() {
		return startCountry;
	}

	public void setStartCountry(String startCountry) {
		this.startCountry = startCountry;
	}

	public String getEndLocation() {
		return endLocation;
	}

	public void setEndLocation(String endLocation) {
		this.endLocation = endLocation;
	}

	public String getEndCountry() {
		return endCountry;
	}

	public void setEndCountry(String endCountry) {
		this.endCountry = endCountry;
	}

	public String getSourceFormat() {
		return sourceFormat;
	}

	public void setSourceFormat(String sourceFormat) {
		this.sourceFormat = sourceFormat;
	}

	public String getActivityType() {
		return activityType;
	}

	public void setActivityType(String activityType) {
		this.activityType = activityType;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public Instant getEndTime() {
		return endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public Integer getDurationS() {
		return durationS;
	}

	public void setDurationS(Integer durationS) {
		this.durationS = durationS;
	}

	public Integer getMovingTimeS() {
		return movingTimeS;
	}

	public void setMovingTimeS(Integer movingTimeS) {
		this.movingTimeS = movingTimeS;
	}

	public Double getDistanceKm() {
		return distanceKm;
	}

	public void setDistanceKm(Double distanceKm) {
		this.distanceKm = distanceKm;
	}

	public Double getElevationGainM() {
		return elevationGainM;
	}

	public void setElevationGainM(Double elevationGainM) {
		this.elevationGainM = elevationGainM;
	}

	public Integer getAvgHr() {
		return avgHr;
	}

	public void setAvgHr(Integer avgHr) {
		this.avgHr = avgHr;
	}

	public Integer getMaxHr() {
		return maxHr;
	}

	public void setMaxHr(Integer maxHr) {
		this.maxHr = maxHr;
	}

	public Double getAvgSpeedKmh() {
		return avgSpeedKmh;
	}

	public void setAvgSpeedKmh(Double avgSpeedKmh) {
		this.avgSpeedKmh = avgSpeedKmh;
	}

	public Double getMaxSpeedKmh() {
		return maxSpeedKmh;
	}

	public void setMaxSpeedKmh(Double maxSpeedKmh) {
		this.maxSpeedKmh = maxSpeedKmh;
	}

	/**
	 * Never null — guards against Hibernate nulling the embeddable when all weather
	 * columns are null.
	 */
	public Weather getWeather() {
		if (weather == null)
			weather = new Weather();
		return weather;
	}

	public void setWeather(Weather weather) {
		this.weather = weather;
	}

	public Double getStartLat() {
		return startLat;
	}

	public void setStartLat(Double startLat) {
		this.startLat = startLat;
	}

	public Double getStartLon() {
		return startLon;
	}

	public void setStartLon(Double startLon) {
		this.startLon = startLon;
	}

	public Instant getImportedAt() {
		return importedAt;
	}

	public void setImportedAt(Instant importedAt) {
		this.importedAt = importedAt;
	}

	public Set<Tag> getTags() {
		return tags;
	}

	public void setTags(Set<Tag> tags) {
		this.tags = tags;
	}
}
