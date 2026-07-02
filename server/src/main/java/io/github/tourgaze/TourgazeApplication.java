/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze;

import java.io.Console;
import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
		try {
			SpringApplication.run(TourgazeApplication.class, args);
		} catch (Throwable t) {
			// The downgrade guard (old jar vs. newer DB) already printed a clean
			// explanation via DatabaseTooNewFailureAnalyzer. On the bundled .exe the
			// console window would otherwise slam shut before the user can read it,
			// so pause until they acknowledge. No System.exit: just let main return.
			if (hasCause(t, DatabaseTooNewException.class)) {
				waitForAcknowledgement();
				return;
			}
			throw t;
		}
	}

	private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
		for (Throwable c = t; c != null; c = c.getCause())
			if (type.isInstance(c))
				return true;
		return false;
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
