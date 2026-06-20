/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;

import jakarta.persistence.*;

import io.github.tourgaze.util.ShortId;

/**
 * A user-placed point of interest. Has its own geo position and an optional
 * association to a ride: {@code activityId == null} means a "general" marker
 * (e.g. Everest) shown on every map; a non-null id ties it to one ride (e.g. a
 * nice restaurant on that route). Carries an editable label/description and a
 * category that drives its map icon.
 */
@Entity
@Table(name = "marker", indexes = {
		@Index(name = "idx_marker_activity", columnList = "activity_id"),
})
public class Marker extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	/** Ride this marker belongs to, or null for a general (always-shown) marker. */
	@Column(name = "activity_id", length = 26)
	private String activityId;

	@Column(nullable = false)
	private double lat;

	@Column(nullable = false)
	private double lon;

	@Column(nullable = false)
	private String label;

	@Column(columnDefinition = "clob")
	private String description;

	/** Icon category: food, peak, viewpoint, water, repair, star, … */
	@Column(nullable = false, length = 64)
	private String category;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@PrePersist
	void ensureId() {
		if (id == null)
			id = ShortId.next();
		if (createdAt == null)
			createdAt = Instant.now();
		if (category == null || category.isBlank())
			category = "star";
		if (label == null)
			label = "";
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getActivityId() {
		return activityId;
	}

	public void setActivityId(String activityId) {
		this.activityId = activityId;
	}

	public double getLat() {
		return lat;
	}

	public void setLat(double lat) {
		this.lat = lat;
	}

	public double getLon() {
		return lon;
	}

	public void setLon(double lon) {
		this.lon = lon;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
