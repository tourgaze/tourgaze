/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.*;

import io.github.tourgaze.util.ShortId;

@Entity
@Table(name = "app_user")
public class User extends BaseEntity {

	@Id
	@Column(length = 26)
	private String id;

	@Column(nullable = false, unique = true)
	private String username;

	@Column(name = "display_name")
	private String displayName;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "date_of_birth")
	private LocalDate dateOfBirth;

	@Column(name = "height_cm")
	private Integer heightCm;

	@Column(name = "weight_kg")
	private Double weightKg;

	@Column(name = "gender", length = 20)
	private String gender;

	@Column(name = "resting_hr")
	private Integer restingHr;

	@Column(name = "max_hr")
	private Integer maxHr;

	/**
	 * Functional Threshold Power (watts) — the rider's ~1-hour sustainable power.
	 * The power-world equivalent of {@link #maxHr}: anchors power zones, TSS, IF
	 * and power-to-weight. User-entered. Null until set.
	 */
	@Column(name = "ftp_w")
	private Integer ftpW;

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

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Instant createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDate getDateOfBirth() {
		return dateOfBirth;
	}

	public void setDateOfBirth(LocalDate dateOfBirth) {
		this.dateOfBirth = dateOfBirth;
	}

	public Integer getHeightCm() {
		return heightCm;
	}

	public void setHeightCm(Integer heightCm) {
		this.heightCm = heightCm;
	}

	public Double getWeightKg() {
		return weightKg;
	}

	public void setWeightKg(Double weightKg) {
		this.weightKg = weightKg;
	}

	public String getGender() {
		return gender;
	}

	public void setGender(String gender) {
		this.gender = gender;
	}

	public Integer getRestingHr() {
		return restingHr;
	}

	public void setRestingHr(Integer restingHr) {
		this.restingHr = restingHr;
	}

	public Integer getMaxHr() {
		return maxHr;
	}

	public void setMaxHr(Integer maxHr) {
		this.maxHr = maxHr;
	}

	public Integer getFtpW() {
		return ftpW;
	}

	public void setFtpW(Integer ftpW) {
		this.ftpW = ftpW;
	}
}
