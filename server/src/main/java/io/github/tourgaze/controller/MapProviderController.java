/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.github.tourgaze.dto.MapProviderDto;
import io.github.tourgaze.entity.MapProvider;
import io.github.tourgaze.repository.MapProviderRepository;

/**
 * CRUD for user-defined map basemap providers. The built-in catalog lives in
 * {@link io.github.tourgaze.service.TileProviderRegistry}; these custom rows
 * are
 * merged into {@code GET /api/tile-providers} so they appear in the basemap
 * picker like any other provider.
 */
@RestController
@RequestMapping("/api/map-providers")
public class MapProviderController {

	private final MapProviderRepository repo;
	private final io.github.tourgaze.service.mapper.MapProviderMapper mapper;
	private final io.github.tourgaze.service.TileFetcher tileFetcher;
	private final com.fasterxml.jackson.databind.ObjectMapper json;

	public MapProviderController(MapProviderRepository repo,
			io.github.tourgaze.service.mapper.MapProviderMapper mapper,
			io.github.tourgaze.service.TileFetcher tileFetcher,
			com.fasterxml.jackson.databind.ObjectMapper json) {
		this.repo = repo;
		this.mapper = mapper;
		this.tileFetcher = tileFetcher;
		this.json = json;
	}

	@GetMapping
	public List<MapProviderDto> getAll() {
		return repo.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
	}

	@PostMapping
	public ResponseEntity<MapProviderDto> create(@RequestBody MapProviderDto dto) {
		if (dto.name() == null || dto.name().isBlank())
			return ResponseEntity.badRequest().build();
		if (!isValidShape(dto))
			return ResponseEntity.badRequest().build();
		MapProvider p = new MapProvider();
		apply(p, dto);
		p = repo.save(p);
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(p));
	}

	@PutMapping("/{id}")
	public ResponseEntity<MapProviderDto> update(@PathVariable("id") String id, @RequestBody MapProviderDto dto) {
		return repo.findById(id).map(p -> {
			apply(p, dto);
			return ResponseEntity.ok(toDto(repo.save(p)));
		}).orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		repo.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Probe a provider before saving: fetch a sample tile (raster) or the style
	 * JSON (vector) and report whether it actually works. Never persists anything
	 * and always answers 200 with {@code {ok, message}} so the form can show a
	 * clear result instead of a generic HTTP error.
	 */
	@PostMapping("/test")
	public ResponseEntity<java.util.Map<String, Object>> test(@RequestBody MapProviderDto dto) {
		String type = dto.type() == null ? "raster" : dto.type();
		try {
			if ("vector".equals(type)) {
				String url = dto.styleUrl();
				if (url == null || url.isBlank())
					return result(false, "No style URL given.");
				if (!isHttpUrl(url))
					return result(false, "Style URL must start with http:// or https://.");
				var resp = tileFetcher.fetch(url);
				if (resp.statusCode() != 200)
					return result(false, "Style URL returned HTTP " + resp.statusCode() + ".");
				com.fasterxml.jackson.databind.JsonNode node = json.readTree(resp.body());
				if (node == null || !node.has("version"))
					return result(false, "Not a valid MapLibre style JSON (missing \"version\").");
				int layers = node.has("layers") ? node.get("layers").size() : 0;
				return result(true, "Style loaded — " + layers + " layer" + (layers == 1 ? "" : "s") + ".");
			}
			// raster: try the world tile (z0/0/0) — the one address every XYZ source
			// should serve. {s} subdomain placeholder gets a concrete value.
			String tpl = dto.urlTemplate();
			if (tpl == null || tpl.isBlank())
				return result(false, "No tile URL given.");
			String url = tpl.replace("{s}", "a").replace("{z}", "0").replace("{x}", "0").replace("{y}", "0");
			if (!isHttpUrl(url))
				return result(false, "Tile URL must start with http:// or https://.");
			var resp = tileFetcher.fetch(url);
			if (resp.statusCode() != 200)
				return result(false, "Tile server returned HTTP " + resp.statusCode() + ".");
			byte[] body = resp.body();
			if (body == null || body.length == 0)
				return result(false, "Tile server returned an empty response.");
			if (!io.github.tourgaze.util.ImageSniff.isImage(body))
				return result(false, "Response was not an image (" + body.length + " bytes). Check the URL template.");
			return result(true, "Sample tile loaded (" + body.length + " bytes).");
		} catch (Exception e) {
			return result(false, "Could not reach the server: " + e.getMessage());
		}
	}

	private static ResponseEntity<java.util.Map<String, Object>> result(boolean ok, String message) {
		return ResponseEntity.ok(java.util.Map.of("ok", ok, "message", message));
	}

	/**
	 * Only probe plain http/https URLs — blocks {@code file:}, {@code jar:} and
	 * other schemes from reaching the fetcher. Private/loopback hosts are still
	 * allowed on purpose: users legitimately run self-hosted tile servers on the
	 * LAN, so an IP allow/block-list would break that.
	 */
	private static boolean isHttpUrl(String url) {
		try {
			String scheme = java.net.URI.create(url).getScheme();
			return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"));
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** raster needs an upstream url_template; vector needs a style_url. */
	private boolean isValidShape(MapProviderDto dto) {
		String type = dto.type() == null ? "" : dto.type();
		if (type.equals("raster"))
			return dto.urlTemplate() != null && !dto.urlTemplate().isBlank();
		if (type.equals("vector"))
			return dto.styleUrl() != null && !dto.styleUrl().isBlank();
		return false;
	}

	private void apply(MapProvider p, MapProviderDto dto) {
		if (dto.name() != null && !dto.name().isBlank())
			p.setName(dto.name().trim());
		p.setDescription(blankToNull(dto.description()));
		if (dto.type() != null && !dto.type().isBlank())
			p.setType(dto.type());
		p.setUrlTemplate(blankToNull(dto.urlTemplate()));
		p.setStyleUrl(blankToNull(dto.styleUrl()));
		p.setMaxZoom(dto.maxZoom());
		p.setAttribution(blankToNull(dto.attribution()));
		p.setDark(dto.dark());
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	private MapProviderDto toDto(MapProvider p) {
		return mapper.toDto(p);
	}
}
