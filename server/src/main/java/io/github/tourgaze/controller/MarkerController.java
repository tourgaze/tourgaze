/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.github.tourgaze.dto.MarkerDto;
import io.github.tourgaze.entity.Marker;
import io.github.tourgaze.repository.MarkerRepository;

/**
 * CRUD for user-placed global map markers (places, not ride annotations — those
 * are ride events carried on the activity). Every marker is shown on every map.
 */
@RestController
@RequestMapping("/api/markers")
public class MarkerController {

	private final MarkerRepository repo;
	private final io.github.tourgaze.service.mapper.MarkerMapper mapper;

	public MarkerController(MarkerRepository repo, io.github.tourgaze.service.mapper.MarkerMapper mapper) {
		this.repo = repo;
		this.mapper = mapper;
	}

	/** Every marker. */
	@GetMapping
	public List<MarkerDto> all() {
		return repo.findAll().stream().map(mapper::toDto).toList();
	}

	@PostMapping
	public MarkerDto create(@RequestBody MarkerDto dto) {
		Marker m = new Marker();
		m.setLat(dto.lat());
		m.setLon(dto.lon());
		m.setLabel(dto.label() == null ? "" : dto.label());
		m.setDescription(dto.description());
		m.setCategory(dto.category());
		return mapper.toDto(repo.save(m));
	}

	@PatchMapping("/{id}")
	public ResponseEntity<MarkerDto> update(@PathVariable("id") String id, @RequestBody MarkerDto dto) {
		Optional<Marker> opt = repo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		Marker m = opt.get();
		if (dto.label() != null)
			m.setLabel(dto.label());
		if (dto.description() != null)
			m.setDescription(dto.description());
		if (dto.category() != null && !dto.category().isBlank())
			m.setCategory(dto.category());
		// lat/lon are sent on drag-to-move; 0/0 is never a real marker position.
		if (dto.lat() != 0 || dto.lon() != 0) {
			m.setLat(dto.lat());
			m.setLon(dto.lon());
		}
		return ResponseEntity.ok(mapper.toDto(repo.save(m)));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		if (!repo.existsById(id))
			return ResponseEntity.notFound().build();
		repo.deleteById(id);
		return ResponseEntity.noContent().build();
	}
}
