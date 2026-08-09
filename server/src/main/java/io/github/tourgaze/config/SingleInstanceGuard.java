/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Double-start guard for the desktop build. Double-clicking the launcher while
 * TourGaze is already running used to boot a second JVM that died on the port
 * bind — with the splash stuck on "Starting…" and no console to explain why.
 * Instead, probe the app port before Spring boots: if the running instance
 * answers, open the browser at it and let this process exit cleanly.
 *
 * <p>
 * The probe must confirm it is really TourGaze on the port (not some unrelated
 * local server we would silently "open" instead of reporting the port clash),
 * so it checks the served page for the app title. If the port is busy with
 * something else, startup proceeds and fails on the bind as before.
 */
public final class SingleInstanceGuard {

	private static final Logger log = LoggerFactory.getLogger(SingleInstanceGuard.class);

	private static final int DEFAULT_PORT = 8085; // mirrors server.port in application.yaml

	private SingleInstanceGuard() {
	}

	/**
	 * Returns {@code true} when a running TourGaze instance was found on the target
	 * port (browser opened at it, caller should exit) — {@code false} to start
	 * normally.
	 */
	public static boolean redirectToRunningInstance(String[] args) {
		String url = "http://localhost:" + resolvePort(args);
		if (!isTourgazeAt(url))
			return false;
		log.info("TourGaze is already running at {} — opening it instead of starting a second instance.", url);
		BrowserLauncher.openBrowser(url);
		return true;
	}

	/**
	 * The port Spring WILL bind, resolved without booting Spring: command line
	 * ({@code --server.port=…}) beats system property beats {@code SERVER_PORT}
	 * env, else the packaged default. Relaxed-binding exotica isn't needed — the
	 * desktop launcher passes no args at all.
	 */
	private static int resolvePort(String[] args) {
		for (String a : args)
			if (a.startsWith("--server.port="))
				return parsePort(a.substring("--server.port=".length()));
		String prop = System.getProperty("server.port");
		if (prop != null)
			return parsePort(prop);
		String env = System.getenv("SERVER_PORT");
		if (env != null)
			return parsePort(env);
		return DEFAULT_PORT;
	}

	private static int parsePort(String s) {
		try {
			return Integer.parseInt(s.trim());
		} catch (NumberFormatException e) {
			return DEFAULT_PORT;
		}
	}

	/** Quick local probe: does this URL serve the TourGaze SPA right now? */
	private static boolean isTourgazeAt(String url) {
		try {
			HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(2))
					.GET().build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			return resp.statusCode() == 200 && resp.body().contains("TourGaze");
		} catch (Exception e) {
			// Nothing listening (the normal first-start case) or not reachable.
			return false;
		}
	}
}
