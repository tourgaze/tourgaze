/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze;

import java.io.Console;
import java.nio.file.Path;
import java.util.Map;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.PortInUseException;

import io.github.tourgaze.config.DesktopSplash;
import io.github.tourgaze.config.HomeConfigScaffold;
import io.github.tourgaze.config.SingleInstanceGuard;
import io.github.tourgaze.exception.DatabaseTooNewException;

@SpringBootApplication
public class TourgazeApplication {

	public static void main(String[] args) {
		// Persist and compute in UTC regardless of the host machine's timezone.
		// Hibernate binds java.time.Instant to the DB's "timestamp with time zone"
		// columns using the JVM default zone; on a machine set to e.g. Europe/Berlin
		// that shifts every imported ride's stored time by the local offset (-2h in
		// CEST). Pinning the JVM to UTC stores the exact GPS instant. The frontend
		// still renders each time in the viewer's own local zone. (hibernate.jdbc.
		// time_zone does NOT cover this Instant → TIMESTAMP WITH TIME ZONE path.)
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		// Double-start guard: if TourGaze already runs on the target port, open
		// the browser at the running instance and exit instead of booting a second
		// JVM that would die on the port bind (leaving the splash stuck on
		// "Starting…"). System.exit, not return: opening the browser initialises
		// AWT, whose non-daemon threads would otherwise keep the JVM alive.
		if (SingleInstanceGuard.redirectToRunningInstance(args)) {
			System.exit(0);
		}
		// Instant feedback on the desktop build (no console window): show the
		// splash before Spring boots. No-ops when headless (jar/container). It is
		// dismissed on ApplicationReadyEvent by TrayLauncher.
		DesktopSplash.showIfDesktop();
		try {
			SpringApplication app = new SpringApplication(TourgazeApplication.class);
			app.setDefaultProperties(desktopDefaults());
			app.run(args);
		} catch (Throwable t) {
			// Desktop launch: no console to read the failure in, and the splash window
			// would keep the JVM alive on "Starting…" forever — say why, then exit.
			if (DesktopSplash.isShowing()) {
				DesktopSplash.showStartupFailure(startupFailureMessage(t));
				System.exit(1);
			}
			// The downgrade guard (old jar vs. newer DB) already printed a clean
			// explanation via DatabaseTooNewFailureAnalyzer. In a console window that
			// would otherwise slam shut before the user can read it, pause until they
			// acknowledge. No System.exit: just let main return.
			if (findCause(t, DatabaseTooNewException.class) != null) {
				waitForAcknowledgement();
				return;
			}
			throw t;
		}
	}

	/** What the startup-failure dialog says: the fix for the common cases, else the root cause. */
	static String startupFailureMessage(Throwable t) {
		PortInUseException portInUse = findCause(t, PortInUseException.class);
		if (portInUse != null) {
			int port = portInUse.getPort();
			return """
					Port %d is already in use — by another program, or reserved by
					Windows (WSL, Docker and Hyper-V often reserve ports around 8000).

					To use a different port, add these lines to
					%s
					and start TourGaze again:

					server:
					  port: %d
					""".formatted(port, HomeConfigScaffold.userConfigFile(), port == 18085 ? 18086 : 18085);
		}
		DatabaseTooNewException tooNew = findCause(t, DatabaseTooNewException.class);
		if (tooNew != null)
			return tooNew.getMessage();
		Throwable root = t;
		while (root.getCause() != null)
			root = root.getCause();
		String reason = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
		return reason + "\n\nDetails are in logs/tourgaze.log inside the TourGaze data folder\n(default: "
				+ Path.of(System.getProperty("user.home"), ".tourgaze") + ").";
	}

	/**
	 * The desktop bundle (launched with {@code -Djava.awt.headless=false}) runs on a
	 * personal computer — often a laptop on someone else's Wi-Fi — and TourGaze has
	 * no login, so it listens on this computer only. The headless JAR (server / NAS)
	 * and the Docker image keep listening on the network. Default properties are
	 * the weakest config source, so {@code SERVER_ADDRESS=0.0.0.0} or
	 * {@code server.address} in the user config file still opt in to LAN access.
	 */
	static Map<String, Object> desktopDefaults() {
		return "false".equals(System.getProperty("java.awt.headless"))
				? Map.of("server.address", "127.0.0.1")
				: Map.of();
	}

	private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
		for (Throwable c = t; c != null; c = c.getCause())
			if (type.isInstance(c))
				return type.cast(c);
		return null;
	}

	/**
	 * Hold the console open so the user can read the failure message. Only when
	 * attached to an interactive console — under Docker/CI (no TTY) {@code console}
	 * is null and we return immediately rather than hang a headless container.
	 */
	private static void waitForAcknowledgement() {
		Console console = System.console();
		if (console == null)
			return;
		console.printf("%nPress Enter to close this window...%n");
		console.readLine();
	}
}
