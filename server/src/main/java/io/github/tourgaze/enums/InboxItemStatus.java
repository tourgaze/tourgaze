/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The single, consistent status of an inbox item — the one field the frontend
 * keys its rendering off, replacing the previous overlap of
 * {@code parsing}/{@code existingActivityId}/{@code duplicateOfId} booleans.
 *
 * <ul>
 * <li>{@code parsing} — skeleton: listed instantly, not yet parsed by the warm
 * job (distance/type still null). Flips via an SSE push.</li>
 * <li>{@code ready} — parsed and importable. A route-similar ride (if any) is a
 * soft hint, not a blocker.</li>
 * <li>{@code duplicate} — the exact file (content hash) is already in the
 * repository, or its track matches an imported ride. The only action is Dismiss
 * (or, for a delete-from-device source, "Delete from device").</li>
 * </ul>
 */
@Schema(name = "InboxItemStatus", enumAsRef = true, description = "Lifecycle status of an inbox item.")
public enum InboxItemStatus {

	PARSING("parsing"), READY("ready"), DUPLICATE("duplicate");

	private final String wire;

	InboxItemStatus(String wire) {
		this.wire = wire;
	}

	@JsonValue
	public String wire() {
		return wire;
	}
}
