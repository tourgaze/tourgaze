/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * "Is this ride in the woods?" — asks OpenStreetMap (Overpass) for the forest
 * polygons ({@code landuse=forest} / {@code natural=wood}) around a track and
 * reports what fraction of the sampled track points fall inside one. Used only
 * for the handful of rides the cheap heuristic can't split (steep + slow: could
 * be a forest MTB loop or a road mountain pass), so one network call per such
 * ride is fine.
 *
 * <p>
 * Best-effort, mirroring {@link OverpassClient}: {@code null} means "the
 * lookup failed" (offline / rate-limited / timeout) so the caller can leave the
 * ride's gear untouched rather than guess.
 */
@Component
public class ForestClient {

	private static final Logger log = LoggerFactory.getLogger(ForestClient.class);

	/**
	 * Widen the track bbox so a forest whose boundary nodes sit just outside
	 * the ride still gets returned by Overpass's node-in-bbox filter (~1 km).
	 */
	private static final double BBOX_MARGIN_DEG = 0.01;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final ObjectMapper json;
	private final String endpoint;

	public ForestClient(ObjectMapper json,
			@Value("${tourgaze.overpass.url:https://overpass-api.de/api/interpreter}") String endpoint) {
		this.json = json;
		this.endpoint = endpoint;
	}

	/**
	 * Fraction (0..1) of the given {@code [lat, lon]} sample points that fall
	 * inside an OSM forest/wood polygon. {@code null} if the Overpass fetch
	 * failed (so the caller can skip rather than misclassify), {@code 0.0} when
	 * the region genuinely has no mapped forest.
	 */
	public Double forestFraction(List<double[]> points) {
		if (points == null || points.isEmpty())
			return 0.0;
		double minLat = Double.MAX_VALUE, minLon = Double.MAX_VALUE;
		double maxLat = -Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
		for (double[] p : points) {
			minLat = Math.min(minLat, p[0]);
			maxLat = Math.max(maxLat, p[0]);
			minLon = Math.min(minLon, p[1]);
			maxLon = Math.max(maxLon, p[1]);
		}
		List<double[][]> forests = fetchForests(
				minLat - BBOX_MARGIN_DEG, minLon - BBOX_MARGIN_DEG,
				maxLat + BBOX_MARGIN_DEG, maxLon + BBOX_MARGIN_DEG);
		if (forests == null)
			return null; // fetch failed — let the caller leave the ride alone
		if (forests.isEmpty())
			return 0.0;
		int inside = 0;
		for (double[] p : points) {
			for (double[][] poly : forests) {
				if (inPolygon(p[0], p[1], poly)) {
					inside++;
					break;
				}
			}
		}
		return (double) inside / points.size();
	}

	/** Forest polygons (each an array of {@code [lat, lon]} rings) in the bbox. */
	private List<double[][]> fetchForests(double minLat, double minLon, double maxLat, double maxLon) {
		String bbox = minLat + "," + minLon + "," + maxLat + "," + maxLon;
		String query = "[out:json][timeout:25];("
				+ "way[\"landuse\"=\"forest\"](" + bbox + ");"
				+ "way[\"natural\"=\"wood\"](" + bbox + ");"
				+ "relation[\"landuse\"=\"forest\"](" + bbox + ");"
				+ "relation[\"natural\"=\"wood\"](" + bbox + ");"
				+ ");out geom;";
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
					.timeout(Duration.ofSeconds(40))
					.header("User-Agent", "TourGaze/1.0 (local-first ride viewer)")
					.header("Content-Type", "text/plain; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(query))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("[Forest] {} for bbox {} — lookup failed, ride left unclassified", resp.statusCode(), bbox);
				return null;
			}
			return parse(resp.body());
		} catch (Exception e) {
			log.info("[Forest] fetch failed for bbox {}: {}", bbox, e.toString());
			return null;
		}
	}

	private List<double[][]> parse(String body) throws Exception {
		JsonNode root = json.readTree(body);
		JsonNode elements = root.path("elements");
		// Overpass reports soft failures (rate-limit / query timeout) as HTTP 200
		// with a top-level "remark" and no elements — treat as a failure (null).
		boolean empty = elements.isMissingNode() || !elements.iterator().hasNext();
		String remark = root.path("remark").asText("");
		if (empty && !remark.isBlank()) {
			log.warn("[Forest] soft-fail remark — ride left unclassified: {}", remark);
			return null;
		}
		List<double[][]> polys = new ArrayList<>();
		for (JsonNode el : elements) {
			String type = el.path("type").asText();
			if ("way".equals(type)) {
				double[][] ring = ring(el.path("geometry"));
				if (ring != null)
					polys.add(ring);
			} else if ("relation".equals(type)) {
				// Multipolygon: treat each member way's geometry as its own ring. We
				// ignore inner/outer roles — good enough for a "point in the woods?"
				// test (a track point in a clearing is a rare false positive).
				for (JsonNode member : el.path("members")) {
					double[][] ring = ring(member.path("geometry"));
					if (ring != null)
						polys.add(ring);
				}
			}
		}
		return polys;
	}

	/** An Overpass {@code geometry} array → an array of {@code [lat, lon]}. */
	private static double[][] ring(JsonNode geometry) {
		if (!geometry.isArray() || geometry.size() < 3)
			return null;
		double[][] ring = new double[geometry.size()][2];
		int i = 0;
		for (JsonNode pt : geometry) {
			ring[i][0] = pt.path("lat").asDouble();
			ring[i][1] = pt.path("lon").asDouble();
			i++;
		}
		return ring;
	}

	/**
	 * Ray-casting point-in-polygon. At ride scale lat/lon can be treated as a
	 * plane, so this is exact enough for a coverage estimate.
	 */
	private static boolean inPolygon(double lat, double lon, double[][] poly) {
		boolean in = false;
		for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
			double yi = poly[i][0], xi = poly[i][1];
			double yj = poly[j][0], xj = poly[j][1];
			boolean crosses = (yi > lat) != (yj > lat)
					&& lon < (xj - xi) * (lat - yi) / (yj - yi) + xi;
			if (crosses)
				in = !in;
		}
		return in;
	}
}
