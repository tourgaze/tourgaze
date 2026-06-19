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

/**
 * Coverage record: marks a geohash cell as "already fetched from Overpass" so
 * the lazy peak/pass importer never re-queries a region it has seen. Keyed by
 * the same geohash prefix stored on {@link GeoFeature#getGeocell()}.
 */
@Entity
@Table(name = "geo_region")
public class GeoRegion {

	@Id
	@Column(length = 12)
	private String geocell;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	@Column(name = "feature_count", nullable = false)
	private int featureCount;

	public GeoRegion() {
	}

	public GeoRegion(String geocell, int featureCount) {
		this.geocell = geocell;
		this.featureCount = featureCount;
		this.fetchedAt = Instant.now();
	}

	@PrePersist
	void ensureFetchedAt() {
		if (fetchedAt == null)
			fetchedAt = Instant.now();
	}

	public String getGeocell() {
		return geocell;
	}

	public void setGeocell(String geocell) {
		this.geocell = geocell;
	}

	public Instant getFetchedAt() {
		return fetchedAt;
	}

	public void setFetchedAt(Instant fetchedAt) {
		this.fetchedAt = fetchedAt;
	}

	public int getFeatureCount() {
		return featureCount;
	}

	public void setFeatureCount(int featureCount) {
		this.featureCount = featureCount;
	}
}
