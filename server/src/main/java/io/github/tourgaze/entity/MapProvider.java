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
 * A user-defined map basemap provider, managed in Settings → Map providers
 * (alongside the built-in catalog in
 * {@link io.github.tourgaze.service.TileProviderRegistry}).
 *
 * For {@code type = "raster"}, {@code urlTemplate} is the UPSTREAM XYZ tile URL
 * (e.g. {@code https://tile.example.org/{z}/{x}/{y}.png}); the frontend still
 * loads tiles through our proxy (so they get cached), and the proxy resolves
 * the
 * upstream from this row. For {@code type = "vector"}, {@code styleUrl} is the
 * MapLibre style JSON URL MapLibre fetches directly.
 */
@Entity
@Table(name = "map_provider")
public class MapProvider extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	@Column(nullable = false)
	private String name;

	private String description;

	/** "raster" or "vector". */
	@Column(nullable = false, length = 20)
	private String type;

	/** Upstream XYZ template for raster providers. */
	@Column(name = "url_template", length = 1000)
	private String urlTemplate;

	/** MapLibre style JSON URL for vector providers. */
	@Column(name = "style_url", length = 1000)
	private String styleUrl;

	@Column(name = "max_zoom")
	private Integer maxZoom;

	@Column(length = 2000)
	private String attribution;

	@Column(name = "is_dark", nullable = false)
	private boolean dark;

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

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getUrlTemplate() {
		return urlTemplate;
	}

	public void setUrlTemplate(String urlTemplate) {
		this.urlTemplate = urlTemplate;
	}

	public String getStyleUrl() {
		return styleUrl;
	}

	public void setStyleUrl(String styleUrl) {
		this.styleUrl = styleUrl;
	}

	public Integer getMaxZoom() {
		return maxZoom;
	}

	public void setMaxZoom(Integer maxZoom) {
		this.maxZoom = maxZoom;
	}

	public String getAttribution() {
		return attribution;
	}

	public void setAttribution(String attribution) {
		this.attribution = attribution;
	}

	public boolean isDark() {
		return dark;
	}

	public void setDark(boolean dark) {
		this.dark = dark;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}
}
