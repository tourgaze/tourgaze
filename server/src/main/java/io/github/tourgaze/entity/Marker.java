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
 * A user-placed, GLOBAL point of interest — a place that exists independent of
 * any ride (a café, a trailhead, a summit), shown on every map. On-ride
 * annotations ("a shower hit here", "drink break") are not markers — they're
 * {@link io.github.tourgaze.dto.RideEventDto ride events} carried in the
 * activity's attributes. Carries a geo position, an editable label/description
 * and a category that drives its map icon.
 */
@Entity
@Table(name = "marker")
public class Marker extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

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
