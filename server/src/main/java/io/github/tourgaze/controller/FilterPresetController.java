/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import io.github.tourgaze.dto.FilterPresetDto;
import io.github.tourgaze.entity.FilterPreset;
import io.github.tourgaze.repository.FilterPresetRepository;

/**
 * CRUD for named Tours filter/grouping presets. Mirrors {@link TagController} —
 * simple global list, no user scope (there is no server-side principal).
 */
@RestController
@RequestMapping("/api/filter-presets")
public class FilterPresetController {

	private final FilterPresetRepository repo;
	private final io.github.tourgaze.service.mapper.FilterPresetMapper mapper;

	public FilterPresetController(FilterPresetRepository repo,
			io.github.tourgaze.service.mapper.FilterPresetMapper mapper) {
		this.repo = repo;
		this.mapper = mapper;
	}

	@GetMapping
	public List<FilterPresetDto> list() {
		return repo.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
	}

	@PostMapping
	public ResponseEntity<FilterPresetDto> create(@RequestBody FilterPresetDto dto) {
		if (dto.name() == null || dto.name().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		FilterPreset p = new FilterPreset();
		apply(p, dto);
		p = repo.save(p);
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(p));
	}

	@PutMapping("/{id}")
	public FilterPresetDto update(@PathVariable("id") String id, @RequestBody FilterPresetDto dto) {
		FilterPreset p = repo.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Preset not found"));
		if (dto.name() == null || dto.name().isBlank()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
		}
		apply(p, dto);
		return toDto(repo.save(p));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		repo.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Copy editable fields from a DTO onto an entity (name trimmed, blanks → null).
	 */
	private void apply(FilterPreset p, FilterPresetDto dto) {
		p.setName(dto.name().trim());
		p.setQuery(blankToNull(dto.query()));
		p.setGroupBy(blankToNull(dto.groupBy()));
		p.setGroupTagId(blankToNull(dto.groupTagId()));
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s.trim();
	}

	private FilterPresetDto toDto(FilterPreset p) {
		return mapper.toDto(p);
	}
}
