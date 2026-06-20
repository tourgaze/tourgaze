/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.event;

/**
 * Domain events published when an activity is created / updated / deleted.
 * Consumed after-commit (see RideExportService) to keep the per-ride metadata
 * sidecars in sync the moment the DB changes — no waiting for the nightly run.
 */
public final class ActivityEvents {
	private ActivityEvents() {
	}

	/** An activity row was created or updated (id of the changed activity). */
	public record Changed(String activityId) {
	}

	/**
	 * An activity row was deleted — carries its store filename so the sidecar can
	 * be removed.
	 */
	public record Removed(String activityId, String sourceFilename) {
	}
}
