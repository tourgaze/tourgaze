/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.github.tourgaze.dto.MarkerDto;
import io.github.tourgaze.entity.Marker;
import io.github.tourgaze.repository.MarkerRepository;

/**
 * CRUD for user-placed map markers. A ride's map shows its own markers plus all
 * general (activity-less) ones; the global view shows the general ones.
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

	/** All markers (for a global overview). */
	@GetMapping
	public List<MarkerDto> all() {
		return repo.findAll().stream().map(mapper::toDto).toList();
	}

	/** General markers only (not tied to any ride). */
	@GetMapping("/general")
	public List<MarkerDto> general() {
		return repo.findByActivityIdIsNull().stream().map(mapper::toDto).toList();
	}

	/** A ride's own markers + all general markers — what a ride map renders. */
	@GetMapping("/activity/{activityId}")
	public List<MarkerDto> forActivity(@PathVariable("activityId") String activityId) {
		List<Marker> markers = new ArrayList<>(repo.findByActivityId(activityId));
		markers.addAll(repo.findByActivityIdIsNull());
		return markers.stream().map(mapper::toDto).toList();
	}

	@PostMapping
	public MarkerDto create(@RequestBody MarkerDto dto) {
		Marker m = new Marker();
		m.setActivityId(blankToNull(dto.activityId()));
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
		// Scope toggle: the editor always sends activityId (the ride id for a
		// ride marker, or null/blank for a general one), so apply it verbatim.
		m.setActivityId(blankToNull(dto.activityId()));
		// lat/lon are sent on drag-to-move; 0/0 is never a real ride marker.
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

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}
}
