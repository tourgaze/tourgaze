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
 * Hierarchical tag. Each tag may have a parent (forming a tree per root).
 * Activities link to tags via {@link Activity#getTags()}.
 *
 * Mirrors the matrosdms "context" idea — the user composes a few roots
 * (Conditions, Effort, Route, …) and uses leaves to label rides.
 */
@Entity
@Table(name = "tag", indexes = {
		@Index(name = "idx_tag_parent", columnList = "parent_id"),
})
public class Tag extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_id")
	private Tag parent;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(length = 20)
	private String color;

	/**
	 * Optional Lucide icon name (e.g. "Mountain"). Null → render the colour swatch.
	 */
	@Column(length = 60)
	private String icon;

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

	public Tag getParent() {
		return parent;
	}

	public void setParent(Tag parent) {
		this.parent = parent;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
