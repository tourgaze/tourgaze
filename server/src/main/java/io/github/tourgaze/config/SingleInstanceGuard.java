/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

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
 *
 * <p>
 * Desktop launches only (the bundle passes {@code -Djava.awt.headless=false}):
 * a dev backend or server JAR started next to a running desktop app must start
 * (or fail) on its own, not redirect to it.
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
		if (!"false".equals(System.getProperty("java.awt.headless")))
			return false;
		String url = "http://localhost:" + resolvePort(args, HomeConfigScaffold.userConfigFile());
		if (!isTourgazeAt(url))
			return false;
		log.info("TourGaze is already running at {} — opening it instead of starting a second instance.", url);
		BrowserLauncher.openBrowser(url);
		return true;
	}

	/**
	 * The port Spring WILL bind, resolved without booting Spring: command line
	 * ({@code --server.port=…}) beats system property beats {@code SERVER_PORT}
	 * env beats the user config file (where the "port in use" dialog tells people
	 * to move it), else the packaged default. Relaxed-binding exotica isn't needed —
	 * the desktop launcher passes no args at all.
	 */
	static int resolvePort(String[] args, Path userConfig) {
		for (String a : args)
			if (a.startsWith("--server.port="))
				return parsePort(a.substring("--server.port=".length()));
		String prop = System.getProperty("server.port");
		if (prop != null)
			return parsePort(prop);
		String env = System.getenv("SERVER_PORT");
		if (env != null)
			return parsePort(env);
		Object configured = portFromUserConfig(userConfig);
		if (configured != null)
			return parsePort(configured.toString());
		return DEFAULT_PORT;
	}

	/** {@code server.port} from the user config file — nested or flat key — or {@code null}. */
	private static Object portFromUserConfig(Path file) {
		if (!Files.isRegularFile(file))
			return null;
		try (Reader reader = Files.newBufferedReader(file)) {
			if (new Yaml().load(reader) instanceof Map<?, ?> root) {
				if (root.get("server.port") != null)
					return root.get("server.port");
				if (root.get("server") instanceof Map<?, ?> server)
					return server.get("port");
			}
		} catch (Exception e) {
			// Unreadable or malformed — Spring reports that properly at boot.
		}
		return null;
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
