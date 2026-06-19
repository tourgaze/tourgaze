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
 * A cached OSM point feature — a mountain pass/saddle or a named peak — used to
 * auto-detect ride highlights. This is reference data fetched from Overpass and
 * memoised locally (keyed by OSM node id), not user-editable, so it skips the
 * {@code BaseEntity} optimistic-lock/audit columns. Names are carried in the
 * default plus localized variants ({@code name:en} / {@code name:de}) straight
 * from OSM so the UI can show them in the user's chosen language.
 *
 * {@code geocell} is the geohash prefix the node falls in — the same coverage
 * key {@link GeoRegion} records as "already fetched", so a ride's region lookup
 * is a cheap indexed prefix match.
 */
@Entity
@Table(name = "geo_feature", indexes = {
		@Index(name = "idx_geo_feature_cell", columnList = "geocell"),
		@Index(name = "idx_geo_feature_type", columnList = "type"),
})
public class GeoFeature {

	/** OSM node id — globally unique, so it doubles as our primary key. */
	@Id
	@Column(name = "osm_id")
	private Long osmId;

	/** PASS (mountain_pass / saddle) or PEAK (named natural=peak). */
	@Column(nullable = false, length = 8)
	private String type;

	@Column(length = 200)
	private String name;

	@Column(name = "name_en", length = 200)
	private String nameEn;

	@Column(name = "name_de", length = 200)
	private String nameDe;

	@Column(nullable = false)
	private double lat;

	@Column(nullable = false)
	private double lon;

	/** Elevation in metres (OSM {@code ele} tag), if tagged. */
	@Column(name = "ele_m")
	private Double eleM;

	@Column(length = 32)
	private String wikidata;

	/**
	 * Geohash prefix this node lives in — matches {@link GeoRegion#getGeocell()}.
	 */
	@Column(nullable = false, length = 12)
	private String geocell;

	@Column(name = "fetched_at", nullable = false)
	private Instant fetchedAt;

	@PrePersist
	void ensureFetchedAt() {
		if (fetchedAt == null)
			fetchedAt = Instant.now();
	}

	public Long getOsmId() {
		return osmId;
	}

	public void setOsmId(Long osmId) {
		this.osmId = osmId;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getNameEn() {
		return nameEn;
	}

	public void setNameEn(String nameEn) {
		this.nameEn = nameEn;
	}

	public String getNameDe() {
		return nameDe;
	}

	public void setNameDe(String nameDe) {
		this.nameDe = nameDe;
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

	public Double getEleM() {
		return eleM;
	}

	public void setEleM(Double eleM) {
		this.eleM = eleM;
	}

	public String getWikidata() {
		return wikidata;
	}

	public void setWikidata(String wikidata) {
		this.wikidata = wikidata;
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
}
