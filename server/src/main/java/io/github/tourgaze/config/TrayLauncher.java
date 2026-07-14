/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;

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
 * console window ({@code jpackage} drops {@code --win-console}). The tray icon
 * is the app's control surface: re-open the UI in the browser, or quit cleanly
 * (graceful shutdown — no killing a console).
 *
 * <p>
 * Only activates when a desktop is actually present: {@link SystemTray} needs a
 * non-headless AWT, which the desktop bundle enables via
 * {@code -Djava.awt.headless=false}. Under Docker/Kubernetes, a plain
 * {@code java -jar} (headless by default), or a display-less server, the guards
 * below no-op silently — so this never breaks a headless run. Companion to
 * {@link BrowserLauncher}, which still auto-opens the browser on startup.
 */
@Component
public class TrayLauncher {

	private static final Logger log = LoggerFactory.getLogger(TrayLauncher.class);

	@Value("${app.system-tray:true}")
	private boolean enableTray;

	private final ConfigurableApplicationContext context;

	/** Kept so we can detach the icon on quit before the JVM exits. */
	private TrayIcon trayIcon;

	public TrayLauncher(ConfigurableApplicationContext context) {
		this.context = context;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady(ApplicationReadyEvent event) {
		if (!enableTray)
			return;
		// isHeadless() is true for containers, headless servers and a default
		// `java -jar`; SystemTray.isSupported() catches desktops without a tray
		// (e.g. some Linux DEs). Either way: skip quietly, never fail startup.
		if (GraphicsEnvironment.isHeadless()) {
			log.info("System tray skipped (headless). Run with -Djava.awt.headless=false to enable.");
			return;
		}
		try {
			if (!SystemTray.isSupported()) {
				log.info("System tray not supported on this platform — skipping.");
				return;
			}
			installTray("http://localhost:" + resolvePort(event.getApplicationContext()));
		} catch (Exception e) {
			// A tray is a convenience, never load-bearing — degrade gracefully.
			log.warn("Could not install system tray ({}). App still running; open the URL manually.",
					e.getMessage());
		}
	}

	private void installTray(String url) throws Exception {
		SystemTray tray = SystemTray.getSystemTray();

		PopupMenu menu = new PopupMenu();
		MenuItem open = new MenuItem("Open TourGaze");
		open.addActionListener(e -> openBrowser(url));
		MenuItem quit = new MenuItem("Quit TourGaze");
		quit.addActionListener(e -> quit());
		menu.add(open);
		menu.addSeparator();
		menu.add(quit);

		trayIcon = new TrayIcon(renderIcon(tray.getTrayIconSize()), "TourGaze", menu);
		trayIcon.setImageAutoSize(true);
		// Left-click / double-click the icon → open the UI, the expected gesture.
		trayIcon.addActionListener(e -> openBrowser(url));

		tray.add(trayIcon);
		log.info("System tray installed — right-click the TourGaze icon for Open / Quit.");
	}

	/** Draw a small brand glyph so no image asset needs to ship. */
	private BufferedImage renderIcon(Dimension size) {
		int w = Math.max(16, size.width);
		int h = Math.max(16, size.height);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		var g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		// Rounded green tile background.
		g.setColor(new Color(0x2E, 0x7D, 0x32));
		g.fillRoundRect(0, 0, w - 1, h - 1, w / 3, h / 3);
		// A white "route" polyline with an end marker — the app's motif.
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(Math.max(1.5f, w / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int[] xs = { w / 5, w / 2, (int) (w * 0.42), (int) (w * 0.78) };
		int[] ys = { (int) (h * 0.72), (int) (h * 0.62), (int) (h * 0.3), (int) (h * 0.24) };
		g.drawPolyline(xs, ys, xs.length);
		int d = Math.max(3, w / 6);
		g.fillOval(xs[xs.length - 1] - d / 2, ys[ys.length - 1] - d / 2, d, d);
		g.dispose();
		return img;
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
