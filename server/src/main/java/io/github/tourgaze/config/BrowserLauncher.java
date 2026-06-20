/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Opens the default browser at the app URL once the server is up — the
 * local-first desktop convenience matros has. Gated on
 * {@code app.start-browser}
 * (default true); turned off for tests and the OpenAPI spec build, and skipped
 * automatically in containers (Docker/Kubernetes) where there is no display.
 *
 * <p>
 * Spring Boot forces {@code java.awt.headless=true} early in startup, which
 * makes {@link Desktop} unavailable; we flip it back just before the (first and
 * only) AWT use, so the toolkit initialises non-headless.
 */
@Component
public class BrowserLauncher {

	private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

	@Value("${app.start-browser:true}")
	private boolean startBrowser;

	@EventListener(ApplicationReadyEvent.class)
	public void onReady(ApplicationReadyEvent event) {
		if (!startBrowser)
			return;
		String url = "http://localhost:" + resolvePort(event.getApplicationContext());
		// Off the main thread so a slow/blocking launcher never holds up startup.
		CompletableFuture.runAsync(() -> openBrowser(url));
	}

	private int resolvePort(ApplicationContext ctx) {
		if (ctx instanceof WebServerApplicationContext web && web.getWebServer() != null)
			return web.getWebServer().getPort();
		return 8085; // configured default
	}

	private void openBrowser(String url) {
		if (isContainer()) {
			log.info("Browser auto-open skipped (container). Open {} manually.", url);
			return;
		}
		try {
			System.setProperty("java.awt.headless", "false");
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
				log.info("Opened browser at {}", url);
			} else {
				log.info("Desktop browse unsupported on this platform. Open {} manually.", url);
			}
		} catch (Exception e) {
			log.warn("Could not auto-open browser ({}). Open {} manually.", e.getMessage(), url);
		}
	}

	/** Docker/Kubernetes have no display — don't even try (and avoid the noise). */
	private boolean isContainer() {
		return Files.exists(Path.of("/.dockerenv"))
				|| System.getenv("KUBERNETES_SERVICE_HOST") != null
				|| System.getenv("CONTAINER") != null;
	}
}
