/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This instance's own extra addresses, from {@code tourgaze.allowed-origins}
 * ({@code TOURGAZE_ALLOWED_ORIGINS}, comma-separated) — typically the public
 * domain a reverse proxy serves TourGaze under. Each entry is trusted twice: as
 * a host name the server may be reached under ({@link ForeignSiteFilter}) and
 * as an {@code Origin} allowed to call the API cross-origin ({@link WebConfig}).
 *
 * <p>
 * Entries are URLs ({@code https://tourgaze.example.com}) or bare host names
 * ({@code tourgaze.example.com}, trusting both http and https). Wildcards are
 * refused at startup: the API has no login, so {@code *} would let every
 * website read and change the library.
 */
@Component
public class AllowedOrigins {

	private final Set<String> origins = new LinkedHashSet<>();
	private final Set<String> hosts = new HashSet<>();

	public AllowedOrigins(@Value("${tourgaze.allowed-origins:}") String config) {
		for (String raw : config.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty())
				continue;
			if (entry.contains("*"))
				throw new IllegalStateException("TOURGAZE_ALLOWED_ORIGINS must list exact addresses, not '" + entry
						+ "': TourGaze has no login, so a wildcard would let any website read and change your library.");
			URI uri = parse(entry.contains("://") ? entry : "http://" + entry, entry);
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			hosts.add(host);
			if (entry.contains("://")) {
				origins.add(origin(uri.getScheme(), host, uri.getPort()));
			} else {
				origins.add(origin("http", host, uri.getPort()));
				origins.add(origin("https", host, uri.getPort()));
			}
		}
	}

	/** Normalised {@code scheme://host[:port]} origins, for the CORS mapping. */
	public List<String> origins() {
		return List.copyOf(origins);
	}

	public boolean isAllowedOrigin(String originHeader) {
		try {
			URI uri = URI.create(originHeader.trim());
			return uri.getScheme() != null && uri.getHost() != null
					&& origins.contains(origin(uri.getScheme(), uri.getHost(), uri.getPort()));
		} catch (IllegalArgumentException e) {
			return false; // "null" and other opaque/garbled origins are never trusted
		}
	}

	/** {@code host} must already be lower-case and port-less. */
	public boolean isAllowedHost(String host) {
		return hosts.contains(host);
	}

	private static URI parse(String url, String entry) {
		try {
			URI uri = URI.create(url);
			String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
			if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null)
				return uri;
		} catch (IllegalArgumentException e) {
			// fall through to the error below
		}
		throw new IllegalStateException("TOURGAZE_ALLOWED_ORIGINS entry '" + entry
				+ "' is not an address like https://tourgaze.example.com");
	}

	/** Browsers omit default ports in {@code Origin}, so normalise them away. */
	private static String origin(String scheme, String host, int port) {
		String s = scheme.toLowerCase(Locale.ROOT);
		boolean defaultPort = port == -1 || (s.equals("http") && port == 80) || (s.equals("https") && port == 443);
		return s + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
	}
}
