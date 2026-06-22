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
 * User-managed ride-event TYPE (masterdata, like {@link Sport}). Gives each event
 * kind a display name, icon and colour for the GUI, and lets a user define their
 * own kinds (drink break, puncture, …). The {@code key} is the stable wire value
 * stored on a ride event ({@code RideEventDto.type}, e.g. "WEATHER_RAIN"); the
 * system emits the seeded ones, the rest are whatever the user adds.
 */
@Entity
@Table(name = "event_type", indexes = {
		// id (PK) and event_key (unique) are auto-indexed; this composite serves the
		// hot lookup: enabled types in display order (GET /api/event-types?enabledOnly).
		@Index(name = "idx_event_type_enabled_ordinal", columnList = "enabled, ordinal"),
})
public class EventType extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	/** Stable wire key (UPPER_SNAKE), e.g. "WEATHER_RAIN", "DRINK_BREAK". */
	@Column(name = "event_key", nullable = false, unique = true, length = 64)
	private String key;

	/** Display text for the GUI, e.g. "Rainfall". */
	@Column(nullable = false, length = 120)
	private String name;

	/** Lucide icon name (e.g. "CloudRain", "CupSoda"). */
	@Column(length = 60)
	private String icon;

	@Column(length = 20)
	private String color;

	/** Sort order in the picker. */
	@Column(nullable = false)
	private int ordinal;

	/** Hidden (not offered in pickers) without deleting — keeps old events valid. */
	@Column(nullable = false)
	private boolean enabled = true;

	/**
	 * System-defined kind the app relies on (e.g. WEATHER_RAIN, emitted by the
	 * importer). Built-ins can be renamed / re-iconed but NOT deleted, so the
	 * import always has its type to resolve.
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
