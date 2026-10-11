/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Window;
import java.io.InputStream;
import java.util.Properties;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

/**
 * A lightweight startup splash for the desktop build. Shown as early as possible
 * in {@code main()} — before Spring boots — so the user gets instant feedback
 * instead of staring at nothing for ~3s after double-clicking (there is no
 * console window any more). {@link TrayLauncher} closes it on
 * {@code ApplicationReadyEvent}.
 *
 * <p>
 * Pure AWT (same toolkit as the tray, no Swing). No-ops when headless
 * (containers, a plain {@code java -jar}, display-less servers); the desktop
 * bundle enables AWT via {@code -Djava.awt.headless=false}.
 */
public final class DesktopSplash {

	private static volatile Window window;
	private static volatile Frame owner;

	/**
	 * Read once at class-load: the splash paints before Spring exists, so the {@code BuildProperties}
	 * bean is not available — but the file behind it is a plain classpath resource, written by the
	 * {@code build-info} goal for both the fat jar and {@code target/classes} in dev. Empty when
	 * absent (e.g. an IDE run that skipped the goal), in which case no version is drawn.
	 */
	private static final String VERSION = readVersion();

	private DesktopSplash() {
	}

	private static String readVersion() {
		try (InputStream in = DesktopSplash.class.getResourceAsStream("/META-INF/build-info.properties")) {
			if (in == null)
				return "";
			Properties p = new Properties();
			p.load(in);
			String v = p.getProperty("build.version", "").trim();
			return v.isEmpty() ? "" : "v" + v;
		} catch (Exception ignore) {
			return "";
		}
	}

	/** Show the splash if a desktop is present; safe to call from {@code main}. */
	public static void showIfDesktop() {
		try {
			if (GraphicsEnvironment.isHeadless())
				return;
			EventQueue.invokeAndWait(DesktopSplash::build);
		} catch (Throwable ignore) {
			// A splash is pure sugar — never let it hold up (or break) startup.
			// Throwable, not Exception: a broken display throws AWTError (an Error).
		}
	}

	/** Whether the splash is up — i.e. a desktop launch, with no console to read errors in. */
	public static boolean isShowing() {
		return window != null;
	}

	/**
	 * Startup failed: replace the splash with a dialog saying why, returning once the
	 * user has dismissed it (the caller then exits). Without this the splash stayed up
	 * forever on "Starting…" — its window kept the JVM alive after {@code main} died —
	 * with the reason only in the log. Swing's JOptionPane is fine here: unlike the
	 * splash, this path isn't startup-time critical.
	 */
	public static void showStartupFailure(String message) {
		close();
		try {
			EventQueue.invokeAndWait(() -> {
				try {
					UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				} catch (Exception ignore) {
					// Swing's own look is fine too.
				}
				JDialog dialog = new JOptionPane(message, JOptionPane.ERROR_MESSAGE)
						.createDialog("TourGaze could not start");
				// If the user clicked elsewhere during startup, Windows' focus-stealing
				// guard would otherwise open it behind other windows — unseen.
				dialog.setAlwaysOnTop(true);
				dialog.setVisible(true); // modal: returns once dismissed
				dialog.dispose();
			});
		} catch (Throwable ignore) {
			// No usable display after all — the log still has the details.
		}
	}

	/** Dispose the splash once the app is up. Safe to call when none is showing. */
	static void close() {
		Window w = window;
		Frame o = owner;
		window = null;
		owner = null;
		EventQueue.invokeLater(() -> {
			if (w != null)
				w.dispose();
			if (o != null)
				o.dispose();
		});
	}

	private static void build() {
		// Hidden owner so the splash Window carries no taskbar entry.
		Frame o = new Frame();
		o.setUndecorated(true);
		o.setType(Window.Type.UTILITY);

		Window w = new SplashWindow(o);
		w.setSize(380, 220);
		w.setLocationRelativeTo(null); // centre on screen
		w.setVisible(true);
		owner = o;
		window = w;
	}

	private static final class SplashWindow extends Window {

		SplashWindow(Frame owner) {
			super(owner);
		}

		@Override
		public void paint(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics;
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			int w = getWidth();
			int h = getHeight();

			// Card: white with a subtle border.
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, w, h);
			g.setColor(new Color(0xE2, 0xE8, 0xF0));
			g.drawRect(0, 0, w - 1, h - 1);

			// Goat badge, centred near the top.
			int badge = 88;
			Graphics2D bg = (Graphics2D) g.create();
			bg.translate((w - badge) / 2.0, 30);
			GoatBadge.paint(bg, badge);
			bg.dispose();

			// Title + status.
			g.setColor(new Color(0x0F, 0x17, 0x2A));
			g.setFont(new Font("SansSerif", Font.BOLD, 24));
			drawCentred(g, "TourGaze", w, 30 + badge + 34);
			g.setColor(new Color(0x64, 0x74, 0x8B));
			g.setFont(new Font("SansSerif", Font.PLAIN, 13));
			drawCentred(g, "Starting…", w, 30 + badge + 58);
			if (!VERSION.isEmpty()) {
				g.setColor(new Color(0x94, 0xA3, 0xB8));
				g.setFont(new Font("SansSerif", Font.PLAIN, 11));
				drawCentred(g, VERSION, w, 30 + badge + 80);
			}
		}

		private void drawCentred(Graphics2D g, String s, int width, int y) {
			FontMetrics fm = g.getFontMetrics();
			g.drawString(s, (width - fm.stringWidth(s)) / 2, y);
		}
	}
}
