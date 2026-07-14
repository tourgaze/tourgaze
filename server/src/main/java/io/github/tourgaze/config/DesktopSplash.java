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

	private DesktopSplash() {
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
		}

		private void drawCentred(Graphics2D g, String s, int width, int y) {
			FontMetrics fm = g.getFontMetrics();
			g.drawString(s, (width - fm.stringWidth(s)) / 2, y);
		}
	}
}
