/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.time.Instant;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Build + DB-schema version info, surfaced on the About page so end users can
 * quote it in bug reports ("app 1.0.0, schema v3, H2 2.3"). Read-only, no auth.
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

	private final ObjectProvider<Flyway> flyway;
	private final ObjectProvider<BuildProperties> buildProperties;
	private final DataSource dataSource;

	public VersionController(ObjectProvider<Flyway> flyway, ObjectProvider<BuildProperties> buildProperties,
			DataSource dataSource) {
		this.flyway = flyway;
		this.buildProperties = buildProperties;
		this.dataSource = dataSource;
	}

	public record VersionDto(String app, String schemaVersion, String schemaDescription,
			Instant schemaInstalledOn, String database) {
	}

	@GetMapping
	public VersionDto get() {
		String schemaVersion = "n/a", schemaDescription = null;
		Instant installedOn = null;
		Flyway fw = flyway.getIfAvailable();
		if (fw != null) {
			MigrationInfo cur = fw.info().current();
			if (cur != null) {
				schemaVersion = cur.getVersion() != null ? cur.getVersion().getVersion() : "(repeatable)";
				schemaDescription = cur.getDescription();
				if (cur.getInstalledOn() != null)
					installedOn = cur.getInstalledOn().toInstant();
			}
		}

		String database = "unknown";
		try (var c = dataSource.getConnection()) {
			var m = c.getMetaData();
			database = m.getDatabaseProductName() + " " + m.getDatabaseProductVersion();
		} catch (Exception ignored) {
			// best-effort — never fail the version lookup over DB metadata
		}

		return new VersionDto(appVersion(), schemaVersion, schemaDescription, installedOn, database);
	}

	/**
	 * Prefer build-info.properties (Maven ${revision}); fall back to the jar
	 * manifest, then "dev" when run unpackaged.
	 */
	private String appVersion() {
		BuildProperties bp = buildProperties.getIfAvailable();
		if (bp != null && bp.getVersion() != null)
			return bp.getVersion();
		String v = VersionController.class.getPackage().getImplementationVersion();
		return v != null ? v : "dev";
	}
}
