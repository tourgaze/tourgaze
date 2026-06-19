/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.apache.commons.imaging.Imaging;
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata;
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter;
import org.apache.commons.imaging.formats.tiff.TiffImageMetadata;
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.tourgaze.repository.SettingRepository;

/**
 * Shared handling for personal media uploads (EditTour drop + AddTour staging):
 * decides which files are accepted, and optionally downscales oversized images
 * before they're written to disk. Videos are stored as-is.
 */
@Component
public class MediaProcessor {

	private static final Logger log = LoggerFactory.getLogger(MediaProcessor.class);

	/** Setting key for the "shrink big uploads" toggle. Absent → on by default. */
	public static final String REDUCE_KEY = "media.reduceImages";
	/**
	 * Longest-edge cap for a downscaled image — plenty for a ride gallery, far
	 * below a 5k original.
	 */
	private static final int MAX_DIM = 2048;
	private static final float JPEG_QUALITY = 0.85f;

	public static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif", "heic", "heif");
	public static final Set<String> VIDEO_EXT = Set.of("mp4", "mov", "m4v", "webm");
	/** Formats ImageIO can reliably read + write, so the only ones we downscale. */
	private static final Set<String> SCALABLE = Set.of("jpg", "jpeg", "png");

	private final SettingRepository settings;

	public MediaProcessor(SettingRepository settings) {
		this.settings = settings;
	}

	/** True for any image or video extension we store as ride media. */
	public boolean isAccepted(String ext) {
		String e = ext == null ? "" : ext.toLowerCase();
		return IMAGE_EXT.contains(e) || VIDEO_EXT.contains(e);
	}

	/**
	 * The bytes to actually persist: a downscaled copy when the reduce setting is
	 * on and the file is a scalable image, otherwise the original bytes. Always
	 * best-effort — any failure falls back to the original.
	 */
	public byte[] process(byte[] data, String ext) {
		String e = ext == null ? "" : ext.toLowerCase();
		if (data == null || !reduceEnabled() || !SCALABLE.contains(e))
			return data;
		return downscale(data, e);
	}

	private boolean reduceEnabled() {
		return settings.findById(REDUCE_KEY)
				.map(s -> !"false".equalsIgnoreCase(s.getValue()))
				.orElse(true);
	}

	private static byte[] downscale(byte[] data, String ext) {
		try {
			BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
			if (src == null)
				return data;
			int w = src.getWidth(), h = src.getHeight();
			int longest = Math.max(w, h);
			if (longest <= MAX_DIM)
				return data; // already small enough

			double scale = (double) MAX_DIM / longest;
			int nw = Math.max(1, (int) Math.round(w * scale));
			int nh = Math.max(1, (int) Math.round(h * scale));
			boolean jpeg = ext.equals("jpg") || ext.equals("jpeg");

			BufferedImage dst = new BufferedImage(nw, nh,
					jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = dst.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			if (jpeg) {
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, nw, nh);
			} // JPEG has no alpha
			g.drawImage(src, 0, 0, nw, nh, null);
			g.dispose();

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (jpeg) {
				ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
				ImageWriteParam param = writer.getDefaultWriteParam();
				param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				param.setCompressionQuality(JPEG_QUALITY);
				try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
					writer.setOutput(ios);
					writer.write(null, new IIOImage(dst, null, null), param);
				} finally {
					writer.dispose();
				}
			} else {
				ImageIO.write(dst, "png", out);
			}
			byte[] resized = out.toByteArray();
			// ImageIO drops EXIF — copy GPS/timestamp back so the geo-matcher
			// still places the photo on the track. (JPEG only; PNG carries none.)
			if (jpeg)
				resized = copyExif(data, resized);
			// Only keep the resized copy if it's genuinely smaller.
			return resized.length > 0 && resized.length < data.length ? resized : data;
		} catch (Exception e) {
			log.debug("[Media] downscale failed ({}): {}", ext, e.getMessage());
			return data;
		}
	}

	/**
	 * Re-attach the original JPEG's EXIF block to the downscaled JPEG
	 * (best-effort).
	 */
	private static byte[] copyExif(byte[] original, byte[] downscaledJpeg) {
		try {
			if (!(Imaging.getMetadata(original) instanceof JpegImageMetadata jmeta))
				return downscaledJpeg;
			TiffImageMetadata exif = jmeta.getExif();
			if (exif == null)
				return downscaledJpeg;
			TiffOutputSet outputSet = exif.getOutputSet();
			if (outputSet == null)
				return downscaledJpeg;
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			new ExifRewriter().updateExifMetadataLossless(downscaledJpeg, out, outputSet);
			return out.toByteArray();
		} catch (Exception e) {
			return downscaledJpeg; // no EXIF / unsupported → keep the clean downscale
		}
	}
}
