/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A small string-keyed JSON value cache persisted to a single file.
 * Consolidates
 * the identical load / flush / dirty bookkeeping that the geocode
 * ({@code PredictionService}) and Wikimedia-geosearch
 * ({@code WikimediaPhotoService})
 * caches each hand-rolled.
 *
 * <p>
 * The owning service decides WHEN to {@link #load()} (typically
 * {@code @PostConstruct}) and {@link #flush()} (a {@code @Scheduled} tick
 * and/or
 * shutdown); this owns the HOW. Thread-safe for concurrent get/put; flush is
 * write-if-dirty so the scheduled tick is cheap when nothing changed.
 */
public final class DiskJsonCache<V> {

	private final ConcurrentHashMap<String, V> map = new ConcurrentHashMap<>();
	private final AtomicBoolean dirty = new AtomicBoolean(false);
	private final ObjectMapper json;
	private final Path file;
	private final TypeReference<Map<String, V>> type;

	public DiskJsonCache(ObjectMapper json, Path file, TypeReference<Map<String, V>> type) {
		this.json = json;
		this.file = file;
		this.type = type;
	}

	public V get(String key) {
		return map.get(key);
	}

	public boolean containsKey(String key) {
		return map.containsKey(key);
	}

	public void put(String key, V value) {
		map.put(key, value);
		dirty.set(true);
	}

	public int size() {
		return map.size();
	}

	/** Load entries from disk (best-effort; ignores a missing/corrupt file). */
	public int load() {
		if (!Files.isRegularFile(file))
			return 0;
		try {
			map.putAll(json.readValue(Files.readAllBytes(file), type));
		} catch (Exception ignore) {
			// best-effort: a corrupt cache just starts empty
		}
		return map.size();
	}

	/**
	 * Write to disk only if changed since the last flush. Returns true if it wrote.
	 */
	public boolean flush() {
		if (!dirty.getAndSet(false))
			return false;
		try {
			Files.createDirectories(file.getParent());
			Files.write(file, json.writeValueAsBytes(map));
			return true;
		} catch (IOException e) {
			dirty.set(true); // retry on the next tick
			return false;
		}
	}
}
