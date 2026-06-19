/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Weather snapshot for a ride — an {@code @Embeddable} value object so the five
 * weather fields live as one tidy group on {@link Activity} instead of
 * cluttering
 * the entity. Stored in the SAME {@code activity} columns (no schema change, no
 * join): {@code weather_temp_c}, {@code weather_humidity_pct},
 * {@code weather_wind_kph},
 * {@code weather_condition}, {@code weather_fetched_at}.
 */
@Embeddable
public class Weather {

	@Column(name = "weather_temp_c")
	private Double tempC;

	@Column(name = "weather_humidity_pct")
	private Integer humidityPct;

	@Column(name = "weather_wind_kph")
	private Double windKph;

	@Column(name = "weather_condition", length = 100)
	private String condition;

	@Column(name = "weather_fetched_at")
	private Instant fetchedAt;

	public Double getTempC() {
		return tempC;
	}

	public void setTempC(Double tempC) {
		this.tempC = tempC;
	}

	public Integer getHumidityPct() {
		return humidityPct;
	}

	public void setHumidityPct(Integer humidityPct) {
		this.humidityPct = humidityPct;
	}

	public Double getWindKph() {
		return windKph;
	}

	public void setWindKph(Double windKph) {
		this.windKph = windKph;
	}

	public String getCondition() {
		return condition;
	}

	public void setCondition(String condition) {
		this.condition = condition;
	}

	public Instant getFetchedAt() {
		return fetchedAt;
	}

	public void setFetchedAt(Instant fetchedAt) {
		this.fetchedAt = fetchedAt;
	}
}
