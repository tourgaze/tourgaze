/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AllowedOriginsTest {

	@Test
	void emptyByDefaultSoTheDevServerPortsAreNoLongerTrusted() {
		AllowedOrigins allowed = new AllowedOrigins("");

		assertThat(allowed.origins()).isEmpty();
		assertThat(allowed.isAllowedOrigin("http://localhost:5173")).isFalse();
	}

	@Test
	void urlEntriesAreNormalised() {
		AllowedOrigins allowed = new AllowedOrigins(" https://Tourgaze.Example.com/ , http://nas.example.com:8085,https://x.example:443");

		assertThat(allowed.origins()).containsExactly(
				"https://tourgaze.example.com", "http://nas.example.com:8085", "https://x.example");
		assertThat(allowed.isAllowedOrigin("https://tourgaze.example.com")).isTrue();
		assertThat(allowed.isAllowedOrigin("http://tourgaze.example.com")).isFalse(); // the scheme counts
		assertThat(allowed.isAllowedOrigin("https://x.example")).isTrue();
		assertThat(allowed.isAllowedHost("tourgaze.example.com")).isTrue();
		assertThat(allowed.isAllowedHost("nas.example.com")).isTrue();
	}

	@Test
	void bareHostTrustsBothSchemes() {
		AllowedOrigins allowed = new AllowedOrigins("tourgaze.example.com");

		assertThat(allowed.origins()).containsExactly("http://tourgaze.example.com", "https://tourgaze.example.com");
		assertThat(allowed.isAllowedHost("tourgaze.example.com")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "*", "https://*.example.com", "https://tourgaze.example.com,*" })
	void wildcardsAreRefused(String config) {
		assertThatThrownBy(() -> new AllowedOrigins(config))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("wildcard");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://files.example.com", "https://" })
	void nonWebAddressesAreRefused(String config) {
		assertThatThrownBy(() -> new AllowedOrigins(config)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void opaqueOriginIsNeverTrusted() {
		assertThat(new AllowedOrigins("https://tourgaze.example.com").isAllowedOrigin("null")).isFalse();
	}
}
