/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;

import jakarta.persistence.*;

import io.github.tourgaze.util.ShortId;

@Entity
@Table(name = "gear", indexes = {
		@Index(name = "idx_gear_user", columnList = "user_id"),
})
public class Gear extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

	@Column(nullable = false)
	private String name;

	/** bike, shoes, wetsuit, etc. */
	private String type;

	private String description;

	/** Optional glyph key (see frontend gearIcons) — used as the replay marker. */
	@Column(length = 32)
	private String icon;

	/**
	 * Motor-assisted (e-bike). A property of the bike, not the ride — lets stats
	 * exclude/segment assisted rides so they don't skew speed/power records.
	 */
	@Column(nullable = false)
	private boolean assisted = false;

	/**
	 * Gear weight in kg (e.g. the bike). Added to the rider's body weight to get
	 * the system mass for the cycling-power estimate. Null = unknown.
	 */
	@Column(name = "weight_kg")
	private Double weightKg;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "retired_at")
	private Instant retiredAt;

	@PrePersist
	void ensureId() {
		if (id == null)
			id = ShortId.next();
		if (createdAt == null)
			createdAt = Instant.now();
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

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public boolean isAssisted() {
		return assisted;
	}

	public void setAssisted(boolean assisted) {
		this.assisted = assisted;
	}

	public Double getWeightKg() {
		return weightKg;
	}

	public void setWeightKg(Double weightKg) {
		this.weightKg = weightKg;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public Instant getRetiredAt() {
		return retiredAt;
	}

	public void setRetiredAt(Instant retiredAt) {
		this.retiredAt = retiredAt;
	}
}
