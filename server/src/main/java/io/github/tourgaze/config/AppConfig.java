/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds to "tourgaze.*" properties.
 * Override data directory via TOURGAZE_DATA_DIR env variable or
 * tourgaze.data-dir property.
 */
@Component
@ConfigurationProperties("tourgaze")
public class AppConfig {

	private String dataDir = System.getProperty("user.home") + "/.tourgaze";

	public String getDataDir() {
		return dataDir;
	}

	public void setDataDir(String dataDir) {
		this.dataDir = dataDir;
	}
}
