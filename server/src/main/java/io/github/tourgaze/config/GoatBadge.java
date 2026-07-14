/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Renders the TourGaze goat badge in Java2D — a faithful port of
 * {@code frontend/public/goat.svg} (kept in sync with it). Drawing it as vectors
 * means the tray icon and the startup splash share one crisp, scalable source
 * with no PNG/ICO asset to ship or keep in sync.
 *
 * <p>
 * Coordinates are the SVG's 0..64 viewBox; the goat sits in a group transformed
 * by {@code translate(8,8) scale(2)}, reproduced here exactly.
 */
final class GoatBadge {

	private static final Color BLUE = new Color(0x25, 0x63, 0xEB);

	private GoatBadge() {
	}

	/** A square ARGB image of the badge at {@code size} px. */
	static BufferedImage image(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		paint(g, size);
		g.dispose();
		return img;
	}

	/** Paint the badge into {@code g}, filling a {@code size}×{@code size} box. */
	static void paint(Graphics2D g, double size) {
		Graphics2D gg = (Graphics2D) g.create();
		gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		gg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		gg.scale(size / 64.0, size / 64.0);

		// Rounded blue tile (rect rx=14 → arc diameter 28).
		gg.setColor(BLUE);
		gg.fill(new RoundRectangle2D.Double(0, 0, 64, 64, 28, 28));

		// Goat group: translate(8,8) scale(2), white round strokes, width 1.8.
		gg.translate(8, 8);
		gg.scale(2, 2);
		gg.setColor(Color.WHITE);
		gg.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		Path2D horns = new Path2D.Double();
		horns.moveTo(9.2, 8.4);
		horns.curveTo(7, 4.5, 4.6, 3.5, 3, 5.1);
		horns.moveTo(14.8, 8.4);
		horns.curveTo(17, 4.5, 19.4, 3.5, 21, 5.1);
		gg.draw(horns);

		Path2D head = new Path2D.Double();
		head.moveTo(9.2, 7.8);
		head.quadTo(12, 6.0, 14.8, 7.8);
		head.curveTo(15, 10.8, 14, 13.2, 12, 14.8);
		head.curveTo(10, 13.2, 9, 10.8, 9.2, 7.8);
		head.closePath();
		gg.draw(head);

		Path2D ridge = new Path2D.Double();
		ridge.moveTo(3.5, 21);
		ridge.lineTo(8.5, 17.4);
		ridge.lineTo(12, 20.2);
		ridge.lineTo(15.5, 17.4);
		ridge.lineTo(20.5, 21);
		gg.draw(ridge);

		// Eyes: filled dots (r=0.7), no stroke.
		gg.fill(new Ellipse2D.Double(10.7 - 0.7, 9.6 - 0.7, 1.4, 1.4));
		gg.fill(new Ellipse2D.Double(13.3 - 0.7, 9.6 - 0.7, 1.4, 1.4));
		gg.dispose();
	}
}
