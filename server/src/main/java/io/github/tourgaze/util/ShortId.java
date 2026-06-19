/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;
import com.github.f4b6a3.uuid.codec.base.Base32Codec;

/**
 * Short, time-ordered IDs for entity primary keys.
 *
 * Wraps RFC 9562 UUID v7 (the modern, sortable replacement for v1/v4) and
 * encodes it in Crockford-style Base32 → 26 chars. The first ~10 chars carry
 * the millisecond timestamp, so rows sort by creation time on a B-tree index
 * (good for DB locality, range scans, and "latest first" queries).
 *
 * v7 raw: 01923c01-1234-7abc-bdef-0123456789ab (36 chars + dashes)
 * short: 01H8KZ8K9QF7VABCDF0123456 (26 chars, URL-safe)
 *
 * Reversible via {@link #toUuid(String)} if we ever need the raw value.
 */
public final class ShortId {

	private static final Base32Codec CODEC = new Base32Codec();

	private ShortId() {
	}

	/** Generate a new short ID backed by a fresh UUID v7. */
	public static String next() {
		return CODEC.encode(UuidCreator.getTimeOrderedEpoch());
	}

	/** Round-trip a short ID back to its underlying UUID. */
	public static UUID toUuid(String shortId) {
		return CODEC.decode(shortId);
	}
}
