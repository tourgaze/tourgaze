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

	public MapProviderController(MapProviderRepository repo,
			io.github.tourgaze.service.mapper.MapProviderMapper mapper) {
		this.repo = repo;
		this.mapper = mapper;
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
