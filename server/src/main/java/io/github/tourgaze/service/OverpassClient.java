/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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

import io.github.tourgaze.entity.GeoFeature;

/**
 * Thin Overpass API client: given a bounding box, returns the mountain
 * passes/saddles and named peaks inside it as {@link GeoFeature}s (geocell +
 * fetchedAt left for the caller to stamp). Best-effort — any failure (offline,
 * rate-limit, timeout) yields an empty list rather than throwing, so highlight
 * detection degrades gracefully.
 */
@Component
public class OverpassClient {

	private static final Logger log = LoggerFactory.getLogger(OverpassClient.class);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final ObjectMapper json;
	private final String endpoint;

	public OverpassClient(ObjectMapper json,
			@Value("${tourgaze.overpass.url:https://overpass-api.de/api/interpreter}") String endpoint) {
		this.json = json;
		this.endpoint = endpoint;
	}

	/** Passes + named peaks within the bbox. Empty on any error. */
	public List<GeoFeature> fetchBbox(double minLat, double minLon, double maxLat, double maxLon) {
		String bbox = minLat + "," + minLon + "," + maxLat + "," + maxLon;
		String query = "[out:json][timeout:25];("
				+ "node[\"mountain_pass\"=\"yes\"](" + bbox + ");"
				+ "node[\"natural\"=\"saddle\"](" + bbox + ");"
				+ "node[\"natural\"=\"peak\"][\"name\"](" + bbox + ");"
				+ ");out tags qt;";
		try {
			HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
					.timeout(Duration.ofSeconds(40))
					.header("User-Agent", "TourGaze/1.0 (local-first ride viewer)")
					.header("Content-Type", "text/plain; charset=utf-8")
					.POST(HttpRequest.BodyPublishers.ofString(query))
					.build();
			HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("[Overpass] {} for bbox {} — skipping region", resp.statusCode(), bbox);
				return List.of();
			}
			return parse(resp.body());
		} catch (Exception e) {
			log.info("[Overpass] fetch failed for bbox {}: {}", bbox, e.toString());
			return List.of();
		}
	}

	private List<GeoFeature> parse(String body) throws Exception {
		List<GeoFeature> out = new ArrayList<>();
		JsonNode elements = json.readTree(body).path("elements");
		for (JsonNode el : elements) {
			if (!"node".equals(el.path("type").asText()) || el.path("id").isMissingNode())
				continue;
			JsonNode tags = el.path("tags");
			boolean isPass = "yes".equals(tags.path("mountain_pass").asText())
					|| "saddle".equals(tags.path("natural").asText());
			GeoFeature f = new GeoFeature();
			f.setOsmId(el.path("id").asLong());
			f.setType(isPass ? "PASS" : "PEAK");
			f.setLat(el.path("lat").asDouble());
			f.setLon(el.path("lon").asDouble());
			f.setName(clip(text(tags, "name"), 200));
			f.setNameEn(clip(text(tags, "name:en"), 200));
			f.setNameDe(clip(text(tags, "name:de"), 200));
			f.setWikidata(clip(text(tags, "wikidata"), 32));
			f.setEleM(parseEle(text(tags, "ele")));
			out.add(f);
		}
		return out;
	}

	private static String text(JsonNode tags, String key) {
		JsonNode n = tags.path(key);
		return n.isMissingNode() || n.asText().isBlank() ? null : n.asText();
	}

	private static String clip(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max);
	}

	/**
	 * OSM {@code ele} can be "1234", "1234 m", "1,234" — pull the leading number.
	 */
	private static Double parseEle(String ele) {
		if (ele == null)
			return null;
		var m = java.util.regex.Pattern.compile("-?\\d+(?:\\.\\d+)?").matcher(ele.replace(",", ""));
		return m.find() ? Double.parseDouble(m.group()) : null;
	}
}
