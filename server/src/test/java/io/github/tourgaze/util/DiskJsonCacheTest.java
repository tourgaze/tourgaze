/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

class DiskJsonCacheTest {

	private final ObjectMapper om = new ObjectMapper();

	private DiskJsonCache<String> cache(Path f) {
		return new DiskJsonCache<>(om, f, new TypeReference<java.util.Map<String, String>>() {
		});
	}

	@Test
	void putGetFlushAndReload(@TempDir Path dir) {
		Path f = dir.resolve("c.json");
		DiskJsonCache<String> c = cache(f);
		assertThat(c.get("k")).isNull();

		c.put("k", "v");
		assertThat(c.get("k")).isEqualTo("v");
		assertThat(c.size()).isEqualTo(1);
		assertThat(c.flush()).isTrue(); // dirty → wrote
		assertThat(c.flush()).isFalse(); // clean → no write

		// A fresh instance over the same file reloads the entry.
		DiskJsonCache<String> reloaded = cache(f);
		assertThat(reloaded.load()).isEqualTo(1);
		assertThat(reloaded.get("k")).isEqualTo("v");
	}

	@Test
	void loadMissingFileIsEmpty(@TempDir Path dir) {
		DiskJsonCache<String> c = cache(dir.resolve("nope.json"));
		assertThat(c.load()).isZero();
		assertThat(c.get("x")).isNull();
	}
}
