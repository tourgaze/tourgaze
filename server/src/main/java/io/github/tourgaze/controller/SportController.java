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

import io.github.tourgaze.dto.SportDto;
import io.github.tourgaze.entity.Sport;
import io.github.tourgaze.repository.SportRepository;

/**
 * Sport / activity-type lookup + masterdata service. {@code GET} is the lookup
 * the UI (and API consumers) read to populate sport pickers; the rest is CRUD
 * so
 * each install curates its own list (seeded Garmin-aligned on first run).
 */
@RestController
@RequestMapping("/api/sports")
public class SportController {

	private final SportRepository repo;

	public SportController(SportRepository repo) {
		this.repo = repo;
	}

	/**
	 * Lookup: all sports (ordered). {@code ?enabledOnly=true} hides disabled ones.
	 */
	@GetMapping
	public List<SportDto> list(@RequestParam(value = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
		List<Sport> sports = enabledOnly
				? repo.findByEnabledTrueOrderByOrdinalAscNameAsc()
				: repo.findByOrderByOrdinalAscNameAsc();
		return sports.stream().map(SportController::toDto).toList();
	}

	/** Lookup a single sport by its key (e.g. "gravel_cycling"). */
	@GetMapping("/{key}")
	public ResponseEntity<SportDto> byKey(@PathVariable("key") String key) {
		return repo.findByKeyIgnoreCase(key).map(s -> ResponseEntity.ok(toDto(s)))
				.orElse(ResponseEntity.notFound().build());
	}

	@PostMapping
	public ResponseEntity<SportDto> create(@RequestBody SportDto dto) {
		if (dto.name() == null || dto.name().isBlank())
			return ResponseEntity.badRequest().build();
		String key = (dto.key() != null && !dto.key().isBlank()) ? slug(dto.key()) : slug(dto.name());
		if (repo.findByKeyIgnoreCase(key).isPresent())
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		Sport s = new Sport();
		s.setKey(key);
		apply(s, dto);
		if (dto.ordinal() == 0)
			s.setOrdinal(repo.count() == 0 ? 0 : 999); // new custom sports sort last by default
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(repo.save(s)));
	}

	@PutMapping("/{id}")
	public ResponseEntity<SportDto> update(@PathVariable("id") String id, @RequestBody SportDto dto) {
		return repo.findById(id).map(s -> {
			apply(s, dto);
			return ResponseEntity.ok(toDto(repo.save(s)));
		}).orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Delete a sport. Rides keep their stored key (it just renders as the raw key).
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		repo.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	private static void apply(Sport s, SportDto dto) {
		if (dto.name() != null && !dto.name().isBlank())
			s.setName(dto.name().trim());
		s.setIcon(dto.icon());
		s.setColor(dto.color());
		s.setOrdinal(dto.ordinal());
		s.setEnabled(dto.enabled());
	}

	private static SportDto toDto(Sport s) {
		return new SportDto(s.getId(), s.getKey(), s.getName(), s.getIcon(), s.getColor(), s.getOrdinal(),
				s.isEnabled());
	}

	/** Lowercase, non-alphanumerics → underscore, for a stable key. */
	private static String slug(String v) {
		return v.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
	}
}
