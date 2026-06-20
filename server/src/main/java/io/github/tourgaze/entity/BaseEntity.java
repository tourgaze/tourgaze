/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;

/**
 * Common persistence concerns shared by every mutable entity:
 *
 * - {@code version} — JPA optimistic lock. TourGaze runs async jobs (metadata
 * export, weather backfill, tile warming) that mutate rows concurrently with
 * user edits; the version counter turns a silent lost-update into a loud
 * {@code OptimisticLockingFailureException} (mapped to HTTP 409).
 * - {@code updatedAt} — last-modified timestamp, stamped on insert and update.
 *
 * Each entity keeps its own {@code @PrePersist} for id/created-at generation;
 * JPA invokes this superclass callback as well (superclass first).
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Version
	private Long version;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@PrePersist
	@PreUpdate
	void touchUpdatedAt() {
		updatedAt = Instant.now();
	}

	public Long getVersion() {
		return version;
	}

	public void setVersion(Long version) {
		this.version = version;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(Instant updatedAt) {
		this.updatedAt = updatedAt;
	}
}
