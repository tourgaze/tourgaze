/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Event names emitted on the inbox Server-Sent-Events stream
 * ({@code GET /api/inbox/stream}). Published as a named OpenAPI schema so the
 * stream contract is api-first: the frontend derives its EventSource listener
 * names from the generated type instead of hard-coding magic strings.
 *
 * <ul>
 * <li>{@code connected} — one handshake event right after subscribing.</li>
 * <li>{@code inbox-changed} — the inbox changed (file staged, proposal warmed,
 * imported, ignored, …); the client should refetch {@code GET /api/inbox}.</li>
 * </ul>
 */
@Schema(name = "InboxStreamEvent", enumAsRef = true, description = "Event name on the inbox SSE stream (GET /api/inbox/stream).")
public enum InboxStreamEvent {

	CONNECTED("connected"), INBOX_CHANGED("inbox-changed");

	private final String wire;

	InboxStreamEvent(String wire) {
		this.wire = wire;
	}

	/**
	 * The SSE {@code event:} name as sent on the wire, e.g.
	 * {@code "inbox-changed"}.
	 */
	@JsonValue
	public String wire() {
		return wire;
	}
}
