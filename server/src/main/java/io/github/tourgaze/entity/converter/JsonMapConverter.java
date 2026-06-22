/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.entity.converter;

import java.util.HashMap;
import java.util.Map;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Persists a free-form {@code Map<String,Object>} as JSON text in a single
 * {@code json} column (the matros document-attributes pattern). We convert the
 * map ourselves rather than relying on Hibernate's native JSON type because the
 * dev DB is H2-in-PostgreSQL-mode: with the PostgreSQL dialect, H2 wraps a
 * bound
 * string into a JSON *string* value, so a naive read gets {@code "\"{...}\""}
 * back. {@link #convertToEntityAttribute} therefore parses, and on the
 * "it's a quoted string" case unwraps once and re-parses — so the same column
 * round-trips on both H2 (dev) and real PostgreSQL (prod).
 */
@Converter(autoApply = false)
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public String convertToDatabaseColumn(Map<String, Object> attribute) {
		if (attribute == null || attribute.isEmpty()) {
			return "{}";
		}
		try {
			return MAPPER.writeValueAsString(attribute);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize attributes to JSON", e);
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public Map<String, Object> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) {
			return new HashMap<>();
		}
		try {
			return MAPPER.readValue(dbData, Map.class);
		} catch (Exception first) {
			// H2-in-PG-mode hands back the object double-encoded as a JSON string;
			// unwrap that one level then parse the real object.
			try {
				String unwrapped = MAPPER.readValue(dbData, String.class);
				return MAPPER.readValue(unwrapped, Map.class);
			} catch (Exception second) {
				throw new IllegalStateException("Failed to parse attributes JSON: " + dbData, first);
			}
		}
	}
}
