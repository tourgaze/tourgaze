/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;

import jakarta.persistence.*;

import io.github.tourgaze.util.ShortId;

/**
 * A named, server-persisted Tours search — the matrosdms "perspective" idea
 * applied to the ride list. The user types a JIRA/Lucene-style faceted query
 * in the Tours search bar (e.g. {@code sport:cycling year:2020 tag:climb
 * alpine}), picks a grouping mode, names it, and recalls it later.
 *
 * The query is parsed and applied client-side over the already-loaded activity
 * list, so the server only stores the raw text — no need to model fields here.
 * Global, like {@link Tag}: there is no server-side principal, the "active
 * user" lives only in the frontend.
 */
@Entity
@Table(name = "filter_preset")
public class FilterPreset extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	@Column(nullable = false, length = 120)
	private String name;

	/** Raw faceted query string, parsed client-side. */
	@Column(length = 500)
	private String query;

	@Column(name = "group_by", length = 40)
	private String groupBy;

	/**
	 * Parent tag id when groupBy = 'tag-children' (ad-hoc "group by tag's
	 * children").
	 */
	@Column(name = "group_tag_id", length = 26)
	private String groupTagId;

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

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getQuery() {
		return query;
	}

	public void setQuery(String query) {
		this.query = query;
	}

	public String getGroupBy() {
		return groupBy;
	}

	public void setGroupBy(String groupBy) {
		this.groupBy = groupBy;
	}

	public String getGroupTagId() {
		return groupTagId;
	}

	public void setGroupTagId(String groupTagId) {
		this.groupTagId = groupTagId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
