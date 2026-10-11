/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ForeignSiteFilterTest {

	private ForeignSiteFilter filter = new ForeignSiteFilter(new AllowedOrigins(""));

	@Test
	void desktopAppTalkingToItselfPasses() throws Exception {
		assertPasses(request("POST", "localhost:8085", "http://localhost:8085"));
	}

	@Test
	void requestWithoutOriginPasses() throws Exception {
		assertPasses(request("GET", "localhost:8085", null));
	}

	@Test
	void foreignWebsiteIsRefused() throws Exception {
		assertRefused(request("POST", "localhost:8085", "https://evil.example"));
	}

	@Test
	void opaqueOriginIsRefused() throws Exception {
		assertRefused(request("POST", "localhost:8085", "null"));
	}

	@Test
	void dnsRebindingIsRefused() throws Exception {
		// evil.example re-pointed at 127.0.0.1: same-origin to the browser AND to Spring.
		MockHttpServletResponse response = assertRefused(request("POST", "evil.example:8085", "http://evil.example:8085"));
		assertThat(response.getContentAsString()).contains("TOURGAZE_ALLOWED_ORIGINS");
	}

	@Test
	void rebindingPageCannotSpoofItsWayInWithForwardedHost() throws Exception {
		// The page adds X-Forwarded-Host: localhost and the container trusts it, so
		// the app's own view is localhost — but the raw Host still names the attacker.
		HttpServletRequest seenThroughProxy = asSeenBehindProxy(request("GET", "evil.example:8085", null),
				"http", "localhost", 8085);
		assertRefused(seenThroughProxy);
	}

	@ParameterizedTest
	@ValueSource(strings = { "localhost:8085", "127.0.0.1:8085", "192.168.1.10:8085", "[::1]:8085", "nas:8085",
			"nas.local:8085", "tourgaze.localhost", "nas.home.arpa", "host.docker.internal:8089", "NAS.Local." })
	void localAddressesAreKnown(String host) throws Exception {
		assertPasses(request("GET", host, null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "evil.example", "tourgaze.example.com", "localhost.evil.example",
			"nas.local.evil.example" })
	void otherDomainsAreUnknown(String host) throws Exception {
		assertRefused(request("GET", host, null));
	}

	@Test
	void reverseProxiedSameOriginPassesOnceItsDomainIsListed() throws Exception {
		filter = new ForeignSiteFilter(new AllowedOrigins("https://tourgaze.example.com"));
		// Proxy keeps Host and sends X-Forwarded-Proto: https, so the app sees its public origin.
		HttpServletRequest seenThroughProxy = asSeenBehindProxy(
				request("POST", "tourgaze.example.com", "https://tourgaze.example.com"),
				"https", "tourgaze.example.com", 443);
		assertPasses(seenThroughProxy);
	}

	@Test
	void listedOriginMayCallCrossOrigin() throws Exception {
		filter = new ForeignSiteFilter(new AllowedOrigins("https://tourgaze.example.com"));
		// Proxy without X-Forwarded-Proto: looks cross-origin, but the origin is listed.
		assertPasses(request("POST", "tourgaze:8085", "https://tourgaze.example.com"));
	}

	@Test
	void hostNameStripsPortAndTrailingDot() {
		assertThat(ForeignSiteFilter.hostName("Example.COM.:443")).isEqualTo("example.com");
		assertThat(ForeignSiteFilter.hostName("[::1]:8085")).isEqualTo("[::1]");
		assertThat(ForeignSiteFilter.hostName("localhost")).isEqualTo("localhost");
		assertThat(ForeignSiteFilter.hostName(null)).isNull();
	}

	/** As Tomcat sees a direct request: server name and port come from the Host header. */
	private static MockHttpServletRequest request(String method, String host, String origin) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/users");
		request.addHeader("Host", host);
		if (origin != null)
			request.addHeader("Origin", origin);
		return request;
	}

	/** What the app sees once Tomcat applies X-Forwarded-*; the raw Host header is untouched. */
	private static HttpServletRequest asSeenBehindProxy(HttpServletRequest raw, String scheme, String serverName,
			int port) {
		return new HttpServletRequestWrapper(raw) {
			@Override
			public String getScheme() {
				return scheme;
			}

			@Override
			public String getServerName() {
				return serverName;
			}

			@Override
			public int getServerPort() {
				return port;
			}
		};
	}

	private void assertPasses(HttpServletRequest request) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		assertThat(chain.getRequest()).as("request reached the app").isNotNull();
	}

	private MockHttpServletResponse assertRefused(HttpServletRequest request) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		assertThat(chain.getRequest()).as("request reached the app").isNull();
		assertThat(response.getStatus()).isEqualTo(403);
		return response;
	}
}
