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
 * User-managed sport / activity type (masterdata, like {@link Gear}). Replaces
 * the former fixed enum so each install curates the sports its riders actually
 * do. The {@code key} is the stable wire value stored on
 * {@code activity.activity_type} (and matched against the device-reported sport
 * at import); seeded Garmin-aligned so we don't diverge from the standard.
 */
@Entity
@Table(name = "sport", indexes = {
		// id (PK) and sport_key (unique) are auto-indexed. This composite serves the
		// hot lookup: enabled sports in display order (GET /api/sports?enabledOnly).
		@Index(name = "idx_sport_enabled_ordinal", columnList = "enabled, ordinal"),
})
public class Sport extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	/** Stable wire key (lowercase), e.g. "cycling", "gravel_cycling". */
	@Column(name = "sport_key", nullable = false, unique = true, length = 64)
	private String key;

	@Column(nullable = false, length = 120)
	private String name;

	/** Lucide icon name (e.g. "Bike", "Mountain"). */
	@Column(length = 60)
	private String icon;

	@Column(length = 20)
	private String color;

	/** Sort order in the picker. */
	@Column(nullable = false)
	private int ordinal;

	/** Hidden (not offered in pickers) without deleting — keeps old rides valid. */
	@Column(nullable = false)
	private boolean enabled = true;

	/**
	 * Seeded default — protected from deletion (the shared Garmin-aligned baseline;
	 * the importer maps onto these keys). Can still be renamed / re-iconed / hidden.
	 */
	@Column(nullable = false)
	private boolean builtin = false;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

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

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public int getOrdinal() {
		return ordinal;
	}

	public void setOrdinal(int ordinal) {
		this.ordinal = ordinal;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isBuiltin() {
		return builtin;
	}

	public void setBuiltin(boolean builtin) {
		this.builtin = builtin;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
