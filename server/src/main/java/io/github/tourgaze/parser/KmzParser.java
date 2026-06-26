/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import io.github.tourgaze.util.Geo;

/**
 * KML / KMZ parser, primarily for OpenTracks exports (the Android FOSS
 * tracker).
 *
 * A KMZ is just a ZIP holding a {@code doc.kml}; a bare {@code .kml} is the
 * same
 * XML uncompressed. OpenTracks writes the route as one or more
 * {@code <gx:Track>} elements with interleaved {@code <when>} timestamps and
 * {@code <gx:coord>lon lat ele</gx:coord>} samples, plus an
 * {@code ExtendedData}
 * block of {@code <gx:SimpleArrayData name="heartrate">} sensor values aligned
 * by index. Google-Earth-style KML instead uses {@code <LineString>
 * <coordinates>lon,lat,ele …</coordinates></LineString>} with no time — both
 * are
 * handled, with the gx:Track path preferred.
 *
 * Like {@link GpxParser}, KML carries little summary metadata, so distance,
 * ascent and speeds are derived from the points and sport is left null for the
 * user to fill in on the AddTour form.
 */
@Component
public class KmzParser implements TrackFileParser {

	private static final double ASCENT_NOISE_M = 1.0;

	@Override
	public boolean supports(String format) {
		return "kmz".equalsIgnoreCase(format) || "kml".equalsIgnoreCase(format);
	}

	@Override
	public ParseResult parse(byte[] data) {
		if (data == null || data.length < 16) {
			throw new IllegalArgumentException("File too small to be a valid KML/KMZ file");
		}

		Document doc = readKmlDocument(data);
		List<TrackPoint> points = readGxTracks(doc);
		if (points.isEmpty())
			points = readLineStrings(doc);
		if (points.isEmpty()) {
			throw new IllegalArgumentException("KML/KMZ file contains no track points");
		}

		double distanceM = 0, ascentM = 0, descentM = 0, maxSpeedMs = 0;
		Double prevLat = null, prevLon = null, prevEle = null;
		Instant prevTime = null;
		for (TrackPoint p : points) {
			if (prevLat != null) {
				double seg = Geo.distanceM(prevLat, prevLon, p.lat(), p.lon());
				distanceM += seg;
				if (prevEle != null && p.altM() != null) {
					double climb = p.altM() - prevEle;
					if (climb > ASCENT_NOISE_M)
						ascentM += climb;
					else if (climb < -ASCENT_NOISE_M)
						descentM += -climb;
				}
				if (prevTime != null && p.time() != null) {
					double dt = (p.time().toEpochMilli() - prevTime.toEpochMilli()) / 1000.0;
					if (dt > 0)
						maxSpeedMs = Math.max(maxSpeedMs, seg / dt);
				}
			}
			prevLat = p.lat();
			prevLon = p.lon();
			prevEle = p.altM();
			prevTime = p.time();
		}

		Instant startTime = points.get(0).time();
		Instant endTime = points.get(points.size() - 1).time();
		Integer durationS = (startTime != null && endTime != null)
				? (int) (endTime.getEpochSecond() - startTime.getEpochSecond())
				: null;
		Double avgSpeedMs = (durationS != null && durationS > 0) ? distanceM / durationS : null;

		// Average HR over the samples that recorded one.
		long hrSum = 0;
		int hrCount = 0, hrMax = 0;
		for (TrackPoint p : points) {
			if (p.hr() != null) {
				hrSum += p.hr();
				hrCount++;
				hrMax = Math.max(hrMax, p.hr());
			}
		}
		// Cadence / power come as gx:SimpleArrayData arrays (OpenTracks), like HR.
		int[] cad = arrayStats(doc, "cadence", "cad");
		int[] pow = arrayStats(doc, "power", "watts", "power_w");

		// sport and moving time aren't reliably in KML → left unset (null).
		return ParseResult.builder()
				.points(points)
				.distanceM(distanceM > 0 ? distanceM : null)
				.ascentM(ascentM > 0 ? ascentM : null)
				.descentM(descentM > 0 ? descentM : null)
				.startTime(startTime)
				.endTime(endTime)
				.durationS(durationS)
				.avgHr(hrCount > 0 ? (int) (hrSum / hrCount) : null)
				.maxHr(hrCount > 0 ? hrMax : null)
				.avgSpeedMs(avgSpeedMs)
				.maxSpeedMs(maxSpeedMs > 0 ? maxSpeedMs : null)
				.avgCadence(cad != null ? cad[0] : null)
				.maxCadence(cad != null ? cad[1] : null)
				.avgPowerW(pow != null ? pow[0] : null)
				.maxPowerW(pow != null ? pow[1] : null)
				.build();
	}

	/**
	 * Average + max of a gx:SimpleArrayData series (by name) across every track, or
	 * null if none recorded. Returns {@code [avg, max]}.
	 */
	private int[] arrayStats(Document doc, String... names) {
		long sum = 0;
		int count = 0, max = 0;
		NodeList tracks = doc.getElementsByTagNameNS("*", "Track");
		for (int t = 0; t < tracks.getLength(); t++) {
			for (String v : simpleArray((Element) tracks.item(t), names)) {
				Integer n = parseInt(v);
				if (n != null) {
					sum += n;
					count++;
					max = Math.max(max, n);
				}
			}
		}
		return count > 0 ? new int[] { (int) Math.round((double) sum / count), max } : null;
	}

	/** Unzip a KMZ to its KML payload, or take the bytes as-is if already KML. */
	private Document readKmlDocument(byte[] data) {
		try {
			return parseXml(isZip(data) ? extractKml(data) : data);
		} catch (Exception e) {
			throw new IllegalArgumentException("Failed to parse KML/KMZ file: " + e.getMessage(), e);
		}
	}

	/** XXE-hardened XML parse — KMZ files are untrusted input. */
	private static Document parseXml(byte[] xml) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
		dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		dbf.setExpandEntityReferences(false);
		return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
	}

	/**
	 * Photos embedded in a KMZ: each {@code <PhotoOverlay>} references an image
	 * ({@code <Icon><href>images/1.jpeg</href>}) and carries a location
	 * ({@code <Point><coordinates>lon,lat,ele</coordinates>} or {@code <Camera>})
	 * plus a {@code <TimeStamp>}. OpenTracks exports waypoint photos this way.
	 * Best-effort: a bad/oversized archive yields no photos, never a failed import.
	 */
	@Override
	public List<EmbeddedPhoto> extractPhotos(byte[] data) {
		if (data == null || !isZip(data))
			return List.of();
		try {
			Map<String, byte[]> entries = readZipEntries(data);
			byte[] kml = null;
			for (Map.Entry<String, byte[]> en : entries.entrySet()) {
				String n = en.getKey().toLowerCase();
				if (n.endsWith("doc.kml")) {
					kml = en.getValue();
					break;
				}
				if (n.endsWith(".kml") && kml == null)
					kml = en.getValue();
			}
			if (kml == null)
				return List.of();

			Document doc = parseXml(kml);
			List<EmbeddedPhoto> photos = new ArrayList<>();
			NodeList overlays = doc.getElementsByTagNameNS("*", "PhotoOverlay");
			for (int i = 0; i < overlays.getLength(); i++) {
				Element ov = (Element) overlays.item(i);
				String href = firstDescendantText(ov, "href");
				if (href == null || href.isBlank())
					continue;
				byte[] img = resolveEntry(entries, href);
				if (img == null)
					continue;
				double[] ll = photoCoords(ov); // [lon, lat] or null
				Instant time = parseTime(firstDescendantText(ov, "when"));
				photos.add(new EmbeddedPhoto(
						basename(href), img,
						ll != null ? ll[1] : null,
						ll != null ? ll[0] : null,
						time));
			}
			return photos;
		} catch (Exception e) {
			return List.of();
		}
	}

	/** All file entries of a ZIP into name → bytes. */
	private static Map<String, byte[]> readZipEntries(byte[] data) throws Exception {
		Map<String, byte[]> out = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
			ZipEntry e;
			while ((e = zip.getNextEntry()) != null) {
				if (!e.isDirectory())
					out.put(e.getName(), zip.readAllBytes());
			}
		}
		return out;
	}

	/**
	 * A PhotoOverlay's location: prefer {@code <Point><coordinates>}, else
	 * {@code <Camera>}.
	 */
	private static double[] photoCoords(Element overlay) {
		String coords = firstDescendantText(overlay, "coordinates");
		if (coords != null && !coords.isBlank()) {
			double[] c = parseCoordComma(coords);
			if (c != null)
				return c;
		}
		String lon = firstDescendantText(overlay, "longitude");
		String lat = firstDescendantText(overlay, "latitude");
		try {
			if (lon != null && lat != null)
				return new double[] { Double.parseDouble(lon), Double.parseDouble(lat) };
		} catch (NumberFormatException ignored) {
			/* fall through */ }
		return null;
	}

	/** Resolve a KML href against the archive: exact path, else by basename. */
	private static byte[] resolveEntry(Map<String, byte[]> entries, String href) {
		String want = href.replaceFirst("^\\./", "");
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (e.getKey().equalsIgnoreCase(want))
				return e.getValue();
		}
		String base = basename(want).toLowerCase();
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			if (basename(e.getKey()).toLowerCase().equals(base))
				return e.getValue();
		}
		return null;
	}

	private static String basename(String path) {
		String p = path.replace('\\', '/');
		int slash = p.lastIndexOf('/');
		return slash >= 0 ? p.substring(slash + 1) : p;
	}

	/** Text of the first descendant element with the given local name, or null. */
	private static String firstDescendantText(Element parent, String localName) {
		NodeList ns = parent.getElementsByTagNameNS("*", localName);
		if (ns.getLength() == 0)
			return null;
		String t = ns.item(0).getTextContent();
		return t == null ? null : t.trim();
	}

	private static boolean isZip(byte[] d) {
		return d.length >= 4 && d[0] == 'P' && d[1] == 'K' && (d[2] == 3 || d[2] == 5 || d[2] == 7);
	}

	/** Pull the first/`.kml` entry out of a KMZ archive. */
	private byte[] extractKml(byte[] data) throws Exception {
		byte[] firstKml = null, anyKml = null;
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
			ZipEntry e;
			while ((e = zip.getNextEntry()) != null) {
				if (e.isDirectory())
					continue;
				String name = e.getName().toLowerCase();
				if (!name.endsWith(".kml"))
					continue;
				byte[] bytes = zip.readAllBytes();
				if (anyKml == null)
					anyKml = bytes;
				// Prefer the conventional doc.kml entry if present.
				if (name.endsWith("doc.kml") || name.equals("kml/doc.kml")) {
					firstKml = bytes;
					break;
				}
			}
		}
		byte[] kml = firstKml != null ? firstKml : anyKml;
		if (kml == null)
			throw new IllegalArgumentException("KMZ archive has no .kml entry");
		return kml;
	}

	/**
	 * All points across every {@code <gx:Track>}, with per-track HR aligned by
	 * index.
	 */
	private List<TrackPoint> readGxTracks(Document doc) {
		List<TrackPoint> out = new ArrayList<>();
		NodeList tracks = doc.getElementsByTagNameNS("*", "Track");
		for (int t = 0; t < tracks.getLength(); t++) {
			Element track = (Element) tracks.item(t);
			List<String> whens = directChildText(track, "when");
			List<String> coords = directChildText(track, "coord");
			List<String> hr = simpleArray(track, "heartrate", "heart_rate", "hr");
			List<String> cad = simpleArray(track, "cadence", "cad");
			List<String> pow = simpleArray(track, "power", "watts", "power_w");
			for (int i = 0; i < coords.size(); i++) {
				double[] c = parseCoordSpace(coords.get(i));
				if (c == null)
					continue;
				Instant time = i < whens.size() ? parseTime(whens.get(i)) : null;
				Integer h = parseInt(i < hr.size() ? hr.get(i) : null);
				Integer cd = parseInt(i < cad.size() ? cad.get(i) : null);
				Integer pw = parseInt(i < pow.size() ? pow.get(i) : null);
				out.add(new TrackPoint(time, c[1], c[0], c.length > 2 ? c[2] : null, h, null, cd, pw));
			}
		}
		return out;
	}

	/** Fallback: {@code <LineString><coordinates>} (comma-separated, no time). */
	private List<TrackPoint> readLineStrings(Document doc) {
		List<TrackPoint> out = new ArrayList<>();
		NodeList coords = doc.getElementsByTagNameNS("*", "coordinates");
		for (int i = 0; i < coords.getLength(); i++) {
			String text = coords.item(i).getTextContent();
			if (text == null)
				continue;
			for (String tuple : text.trim().split("\\s+")) {
				double[] c = parseCoordComma(tuple);
				if (c != null)
					out.add(new TrackPoint(null, c[1], c[0], c.length > 2 ? c[2] : null, null, null));
			}
		}
		return out;
	}

	/**
	 * Text of direct child elements with the given local name, in document order.
	 */
	private List<String> directChildText(Element parent, String localName) {
		List<String> out = new ArrayList<>();
		NodeList kids = parent.getChildNodes();
		for (int i = 0; i < kids.getLength(); i++) {
			Node n = kids.item(i);
			if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
				out.add(n.getTextContent().trim());
			}
		}
		return out;
	}

	/**
	 * Values of the first {@code <gx:SimpleArrayData>} whose name matches, in
	 * order.
	 */
	private List<String> simpleArray(Element track, String... names) {
		NodeList arrays = track.getElementsByTagNameNS("*", "SimpleArrayData");
		for (int i = 0; i < arrays.getLength(); i++) {
			Element arr = (Element) arrays.item(i);
			String name = arr.getAttribute("name").toLowerCase();
			for (String want : names) {
				if (want.equals(name)) {
					List<String> vals = new ArrayList<>();
					NodeList values = arr.getElementsByTagNameNS("*", "value");
					for (int v = 0; v < values.getLength(); v++)
						vals.add(values.item(v).getTextContent().trim());
					return vals;
				}
			}
		}
		return List.of();
	}

	private static double[] parseCoordSpace(String s) {
		return parseTriple(s.trim().split("\\s+"));
	}

	private static double[] parseCoordComma(String s) {
		return parseTriple(s.trim().split(","));
	}

	private static double[] parseTriple(String[] parts) {
		try {
			if (parts.length < 2)
				return null;
			double lon = Double.parseDouble(parts[0]);
			double lat = Double.parseDouble(parts[1]);
			if (parts.length >= 3 && !parts[2].isBlank()) {
				return new double[] { lon, lat, Double.parseDouble(parts[2]) };
			}
			return new double[] { lon, lat };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Instant parseTime(String s) {
		if (s == null || s.isBlank())
			return null;
		try {
			return Instant.parse(s);
		} catch (Exception ignored) {
			try {
				return OffsetDateTime.parse(s).toInstant();
			} catch (Exception ignored2) {
				return null;
			}
		}
	}

	private static Integer parseInt(String s) {
		if (s == null || s.isBlank())
			return null;
		try {
			return (int) Math.round(Double.parseDouble(s.trim()));
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
