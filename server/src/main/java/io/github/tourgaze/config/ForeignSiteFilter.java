/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps other websites out. TourGaze has no login, so without this any page the
 * user happens to visit could read or change their library through their own
 * browser. Do not remove it as "CORS clutter" — it is the API's only protection
 * against foreign sites. Two checks, on every request:
 *
 * <ol>
 * <li><b>Host</b> — the server must be addressed by a name it can legitimately
 * be reached under: localhost, an IP address, a local-network name, or one of
 * {@link AllowedOrigins}. This defeats DNS rebinding, where a site re-points
 * its own domain at 127.0.0.1 (or the NAS) and so looks same-origin to the
 * browser. Checks the raw {@code Host} header on purpose, never the
 * proxy-forwarded host: a rebinding page can add {@code X-Forwarded-Host}
 * itself.</li>
 * <li><b>Origin</b> — a request carrying an {@code Origin} must come from this
 * instance's own origin (as seen through a reverse proxy — the Docker image
 * trusts {@code X-Forwarded-*}) or from {@link AllowedOrigins}. This defeats
 * plain cross-site requests, including the uploads and form posts a browser
 * sends without a CORS preflight.</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ForeignSiteFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(ForeignSiteFilter.class);

	private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

	/**
	 * Reserved for local use — no public DNS answers for these, so an outside
	 * attacker can't point one at anything.
	 */
	private static final List<String> LOCAL_SUFFIXES = List.of(".localhost", ".local", ".home.arpa", ".internal");

	/** Each refusal is logged once, so a misconfigured proxy can't flood the log. */
	private static final int MAX_LOGGED = 100;

	private final AllowedOrigins allowed;
	private final Set<String> logged = ConcurrentHashMap.newKeySet();

	public ForeignSiteFilter(AllowedOrigins allowed) {
		this.allowed = allowed;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String host = hostName(request.getHeader(HttpHeaders.HOST));
		if (host != null && !isKnownHost(host)) {
			logOnce("host:" + host, "[Security] Refused a request addressed to unknown host '{}'. If you reach "
					+ "TourGaze under this name, add it to TOURGAZE_ALLOWED_ORIGINS.", host);
			reject(response, "TourGaze refused this request: '" + host + "' is not a known address of this server.\n"
					+ "If you reach TourGaze under this name, add it to TOURGAZE_ALLOWED_ORIGINS,\n"
					+ "e.g. TOURGAZE_ALLOWED_ORIGINS=https://" + host + "\n");
			return;
		}
		String origin = request.getHeader(HttpHeaders.ORIGIN);
		if (origin != null && CorsUtils.isCorsRequest(request) && !allowed.isAllowedOrigin(origin)) {
			logOnce("origin:" + origin, "[Security] Refused a request from another website ({}). If that is your own "
					+ "TourGaze address behind a reverse proxy, make the proxy send X-Forwarded-Proto, "
					+ "or add it to TOURGAZE_ALLOWED_ORIGINS.", origin);
			reject(response, "TourGaze refused a request from another website (" + origin + ").\n");
			return;
		}
		chain.doFilter(request, response);
	}

	/**
	 * Lower-case host name from a {@code Host} header, port and trailing dot
	 * stripped; IPv6 literals keep their brackets. {@code null} when absent.
	 */
	static String hostName(String hostHeader) {
		if (hostHeader == null || hostHeader.isBlank())
			return null;
		String h = hostHeader.trim().toLowerCase(Locale.ROOT);
		if (h.startsWith("[")) {
			int end = h.indexOf(']');
			return end < 0 ? h : h.substring(0, end + 1);
		}
		int colon = h.lastIndexOf(':');
		if (colon >= 0)
			h = h.substring(0, colon);
		return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
	}

	private boolean isKnownHost(String host) {
		if (host.startsWith("[") || IPV4.matcher(host).matches())
			return true; // IP address — rebinding needs a domain name the attacker controls
		if (!host.contains("."))
			return true; // localhost, or a single-label LAN name ("nas", the PC's own name)
		for (String suffix : LOCAL_SUFFIXES)
			if (host.endsWith(suffix))
				return true;
		return allowed.isAllowedHost(host);
	}

	private void logOnce(String key, String message, String value) {
		if (logged.size() < MAX_LOGGED && logged.add(key))
			log.warn(message, value);
	}

	private static void reject(HttpServletResponse response, String message) throws IOException {
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType("text/plain;charset=UTF-8");
		response.setHeader("X-Content-Type-Options", "nosniff");
		response.getWriter().write(message);
	}
}
