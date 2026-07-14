/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.net.URI;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Puts TourGaze in the OS system tray so the desktop build can run without a
 * console window ({@code jpackage} drops {@code --win-console}). The tray icon —
 * the app's goat badge, drawn by {@link GoatBadge} — is the control surface:
 * re-open the UI, view the logs (since there's no console), or quit cleanly.
 *
 * <p>
 * Only activates when a desktop is present: {@link SystemTray} needs a
 * non-headless AWT, which the desktop bundle enables via
 * {@code -Djava.awt.headless=false}. Under Docker/Kubernetes, a plain
 * {@code java -jar} (headless by default), or a display-less server, the guards
 * below no-op silently — so this never breaks a headless run. Companion to
 * {@link BrowserLauncher} (auto-opens the browser) and {@link DesktopSplash}
 * (the startup splash this closes on ready).
 */
@Component
public class TrayLauncher {

	private static final Logger log = LoggerFactory.getLogger(TrayLauncher.class);

	@Value("${app.system-tray:true}")
	private boolean enableTray;

	@Value("${tourgaze.data-dir}")
	private String dataDir;

	private final ConfigurableApplicationContext context;

	/** Kept so we can detach the icon on quit before the JVM exits. */
	private TrayIcon trayIcon;

	public TrayLauncher(ConfigurableApplicationContext context) {
		this.context = context;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady(ApplicationReadyEvent event) {
		// The whole desktop-chrome path (splash + tray) is optional sugar and must
		// NEVER break startup — a throwing ApplicationReadyEvent listener would.
		// Catch Throwable, not just Exception: a broken/absent display throws
		// AWTError (an Error). Covers Windows, macOS and every Linux DE — where the
		// tray is unsupported (headless server, GNOME without AppIndicator, a plain
		// `java -jar`) this simply logs and the app runs on unaffected.
		try {
			// Always dismiss the startup splash once we're up, tray or not.
			DesktopSplash.close();

			if (!enableTray)
				return;
			if (GraphicsEnvironment.isHeadless()) {
				log.info("System tray skipped (headless). Run with -Djava.awt.headless=false to enable.");
				return;
			}
			if (!SystemTray.isSupported()) {
				log.info("System tray not supported on this desktop — skipping (open the URL manually).");
				return;
			}
			installTray("http://localhost:" + resolvePort(event.getApplicationContext()));
		} catch (Throwable t) {
			log.warn("Desktop tray/splash unavailable ({}); the app is running normally.", t.toString());
		}
	}

	private void installTray(String url) throws Exception {
		SystemTray tray = SystemTray.getSystemTray();

		PopupMenu menu = new PopupMenu();
		MenuItem open = new MenuItem("Open TourGaze");
		open.addActionListener(e -> openBrowser(url));
		MenuItem logs = new MenuItem("Show Logs");
		logs.addActionListener(e -> LogWindow.showOrFocus(logFile()));
		MenuItem quit = new MenuItem("Quit TourGaze");
		quit.addActionListener(e -> quit());
		menu.add(open);
		menu.add(logs);
		menu.addSeparator();
		menu.add(quit);

		// Icon rendered at the platform's preferred tray size for crispness.
		trayIcon = new TrayIcon(GoatBadge.image(tray.getTrayIconSize().width), "TourGaze", menu);
		trayIcon.setImageAutoSize(true);
		// Left-click / double-click the icon → open the UI, the expected gesture.
		trayIcon.addActionListener(e -> openBrowser(url));

		tray.add(trayIcon);
		log.info("System tray installed — right-click the TourGaze icon for Open / Show Logs / Quit.");
	}

	/** {@code <data-dir>/logs/tourgaze.log} — the file logback-spring.xml writes. */
	private Path logFile() {
		return Path.of(dataDir, "logs", "tourgaze.log");
	}

	private void openBrowser(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
				Desktop.getDesktop().browse(URI.create(url));
			else
				log.info("Desktop browse unsupported. Open {} manually.", url);
		} catch (Exception e) {
			log.warn("Could not open browser ({}). Open {} manually.", e.getMessage(), url);
		}
	}

	/**
	 * Detach the icon, then close the context on a background thread so a
	 * graceful shutdown (in-flight requests) can run without freezing the tray
	 * event thread. {@link SpringApplication#exit} returns the exit code once the
	 * context is closed.
	 */
	private void quit() {
		if (trayIcon != null)
			SystemTray.getSystemTray().remove(trayIcon);
		new Thread(() -> {
			int code = SpringApplication.exit(context, () -> 0);
			System.exit(code);
		}, "tourgaze-shutdown").start();
	}

	private int resolvePort(ApplicationContext ctx) {
		if (ctx instanceof WebServerApplicationContext web && web.getWebServer() != null)
			return web.getWebServer().getPort();
		return 8085; // configured default
	}
}
