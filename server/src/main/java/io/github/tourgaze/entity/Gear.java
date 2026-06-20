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
