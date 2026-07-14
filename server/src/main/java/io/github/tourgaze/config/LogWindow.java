/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.awt.BorderLayout;
import java.awt.Button;
import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextArea;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Timer;
import java.util.TimerTask;

/**
 * A tail-follow viewer for {@code <data-dir>/logs/tourgaze.log}, opened from the
 * system tray. With the console gone (windowed desktop build), this is how a user
 * peeks at what the app is doing. Single reusable window; a 1s timer streams
 * appended bytes and auto-scrolls, resetting cleanly if the file rolls over.
 *
 * <p>
 * Pure AWT ({@link Frame}/{@link TextArea}) to match the tray — no Swing. File
 * I/O runs on the timer thread; only the text update hops onto the EDT.
 */
final class LogWindow {

	/** Cap the on-screen buffer so a long-running session can't grow unbounded. */
	private static final int MAX_CHARS = 600_000;
	private static final int INITIAL_TAIL_BYTES = 128 * 1024;

	private static Frame frame;
	private static Timer timer;
	private static long offset;

	private LogWindow() {
	}

	/** Open the viewer (or focus it if already open) for the given log file. */
	static void showOrFocus(Path logFile) {
		EventQueue.invokeLater(() -> {
			if (frame != null) {
				frame.setVisible(true);
				frame.setState(Frame.NORMAL);
				frame.toFront();
				return;
			}
			build(logFile);
		});
	}

	private static void build(Path logFile) {
		TextArea area = new TextArea("", 0, 0, TextArea.SCROLLBARS_BOTH);
		area.setEditable(false);
		area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

		Button clear = new Button("Clear view");
		clear.addActionListener(e -> area.setText(""));
		Button openFolder = new Button("Open folder");
		openFolder.addActionListener(e -> openFolder(logFile));

		Panel buttons = new Panel();
		buttons.add(clear);
		buttons.add(openFolder);
		Panel bar = new Panel(new BorderLayout());
		bar.add(new Label(" " + logFile), BorderLayout.WEST);
		bar.add(buttons, BorderLayout.EAST);

		Frame f = new Frame("TourGaze — Logs");
		f.setIconImage(GoatBadge.image(32));
		f.setLayout(new BorderLayout());
		f.add(area, BorderLayout.CENTER);
		f.add(bar, BorderLayout.SOUTH);
		f.setSize(860, 520);
		f.setLocationRelativeTo(null);
		f.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				dispose();
			}
		});

		offset = seedInitial(logFile, area);
		Timer t = new Timer("tourgaze-logtail", true);
		t.schedule(new TimerTask() {
			@Override
			public void run() {
				pump(logFile, area);
			}
		}, 1000, 1000);

		frame = f;
		timer = t;
		f.setVisible(true);
	}

	private static void dispose() {
		if (timer != null)
			timer.cancel();
		if (frame != null)
			frame.dispose();
		timer = null;
		frame = null;
	}

	/** Load the tail end of the file so the view opens with recent context. */
	private static long seedInitial(Path logFile, TextArea area) {
		try {
			long len = Files.size(logFile);
			long from = Math.max(0, len - INITIAL_TAIL_BYTES);
			try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
				raf.seek(from);
				byte[] buf = new byte[(int) (len - from)];
				raf.readFully(buf);
				String text = new String(buf, StandardCharsets.UTF_8);
				if (from > 0) {
					int nl = text.indexOf('\n'); // drop the partial first line
					if (nl >= 0)
						text = text.substring(nl + 1);
				}
				area.setText(text);
				area.setCaretPosition(area.getText().length());
			}
			return len;
		} catch (IOException e) {
			area.setText("(log file not readable yet: " + logFile + ")\n");
			return 0;
		}
	}

	/** Append any bytes written since the last tick; reset if the file rolled. */
	private static void pump(Path logFile, TextArea area) {
		try {
			long len = Files.size(logFile);
			boolean rolled = len < offset; // rotated/truncated
			if (!rolled && len == offset)
				return;
			String chunk;
			try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
				long from = rolled ? 0 : offset;
				raf.seek(from);
				byte[] buf = new byte[(int) (len - from)];
				raf.readFully(buf);
				chunk = new String(buf, StandardCharsets.UTF_8);
			}
			offset = len;
			EventQueue.invokeLater(() -> append(area, chunk, rolled));
		} catch (IOException ignore) {
			// transient (rotation mid-read) — next tick recovers
		}
	}

	private static void append(TextArea area, String text, boolean rolled) {
		if (rolled)
			area.setText("");
		area.append(text);
		int len = area.getText().length();
		if (len > MAX_CHARS)
			area.replaceRange("", 0, len - MAX_CHARS);
		area.setCaretPosition(area.getText().length()); // scroll to newest
	}

	private static void openFolder(Path logFile) {
		try {
			if (Desktop.isDesktopSupported())
				Desktop.getDesktop().open(logFile.getParent().toFile());
		} catch (IOException ignore) {
			// best effort
		}
	}
}
