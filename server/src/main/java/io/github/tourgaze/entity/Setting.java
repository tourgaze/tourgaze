/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "setting")
public class Setting {

	@Id
	@Column(name = "setting_key")
	private String key;

	// Wide enough for JSON-valued settings (e.g. inbox.sources, a list of
	// {label,path} inbox folders). Must match the V2 migration's VARCHAR(4000).
	@Column(name = "setting_value", length = 4000)
	private String value;

	public Setting() {
	}

	public Setting(String key, String value) {
		this.key = key;
		this.value = value;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}
}
