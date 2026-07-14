/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Build-time only: renders the {@link GoatBadge} to a Windows {@code .ico} (or a
 * {@code .png}) so jpackage can brand the app-image with the TourGaze logo. Run
 * from the {@code windows-portable} Maven profile — never at runtime — so the
 * icon is always regenerated from the one vector source (no committed binary to
 * drift). Renders fine headless (offscreen Java2D), so it works in CI too.
 *
 * <pre>java -cp target/classes io.github.tourgaze.config.IconGenerator out.ico</pre>
 */
public final class IconGenerator {

	/** Sizes a Windows .ico should carry (256 stored as 0 in the header). */
	private static final int[] SIZES = { 16, 32, 48, 64, 128, 256 };

	private IconGenerator() {
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 1) {
			System.err.println("usage: IconGenerator <out.ico|out.png>");
			System.exit(2);
		}
		File out = new File(args[0]);
		if (out.getParentFile() != null)
			out.getParentFile().mkdirs();

		if (args[0].toLowerCase().endsWith(".png"))
			ImageIO.write(GoatBadge.image(256), "png", out);
		else
			writeIco(out);
		System.out.println("Wrote " + out.getAbsolutePath());
	}

	/** Multi-image .ico with PNG-compressed entries (Vista+ supports PNG in ICO). */
	private static void writeIco(File out) throws IOException {
		List<byte[]> pngs = new ArrayList<>();
		for (int size : SIZES) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			ImageIO.write(GoatBadge.image(size), "png", buf);
			pngs.add(buf.toByteArray());
		}
		int n = SIZES.length;
		int offset = 6 + n * 16; // header + directory

		try (DataOutputStream d = new DataOutputStream(
				new BufferedOutputStream(new FileOutputStream(out)))) {
			// ICONDIR
			writeShortLe(d, 0); // reserved
			writeShortLe(d, 1); // type: icon
			writeShortLe(d, n); // image count
			// ICONDIRENTRY per image
			for (int i = 0; i < n; i++) {
				int size = SIZES[i];
				byte[] png = pngs.get(i);
				d.writeByte(size >= 256 ? 0 : size); // width (0 == 256)
				d.writeByte(size >= 256 ? 0 : size); // height
				d.writeByte(0); // palette size (0 = none)
				d.writeByte(0); // reserved
				writeShortLe(d, 1); // colour planes
				writeShortLe(d, 32); // bits per pixel
				writeIntLe(d, png.length); // bytes in image
				writeIntLe(d, offset); // offset to image
				offset += png.length;
			}
			for (byte[] png : pngs)
				d.write(png);
		}
	}

	private static void writeShortLe(DataOutputStream d, int v) throws IOException {
		d.writeByte(v & 0xFF);
		d.writeByte((v >> 8) & 0xFF);
	}

	private static void writeIntLe(DataOutputStream d, int v) throws IOException {
		d.writeByte(v & 0xFF);
		d.writeByte((v >> 8) & 0xFF);
		d.writeByte((v >> 16) & 0xFF);
		d.writeByte((v >> 24) & 0xFF);
	}
}
