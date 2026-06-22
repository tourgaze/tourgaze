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

import io.github.tourgaze.dto.EventTypeDto;
import io.github.tourgaze.entity.EventType;
import io.github.tourgaze.repository.EventTypeRepository;

/**
 * Ride-event type lookup + masterdata service (mirrors {@code SportController}).
 * {@code GET} is the lookup the GUI reads to render an event's label/icon and to
 * populate the "add event" picker; the rest is CRUD so each install curates its
 * own kinds (seeded with the built-ins on first run).
 */
@RestController
@RequestMapping("/api/event-types")
public class EventTypeController {

	private final EventTypeRepository repo;

	public EventTypeController(EventTypeRepository repo) {
		this.repo = repo;
	}

	/** Lookup: all event types (ordered). {@code ?enabledOnly=true} hides disabled. */
	@GetMapping
	public List<EventTypeDto> list(@RequestParam(value = "enabledOnly", defaultValue = "false") boolean enabledOnly) {
		List<EventType> types = enabledOnly
				? repo.findByEnabledTrueOrderByOrdinalAscNameAsc()
				: repo.findByOrderByOrdinalAscNameAsc();
		return types.stream().map(EventTypeController::toDto).toList();
	}

	/** Lookup a single type by its key (e.g. "WEATHER_RAIN"). */
	@GetMapping("/{key}")
	public ResponseEntity<EventTypeDto> byKey(@PathVariable("key") String key) {
		return repo.findByKeyIgnoreCase(key).map(t -> ResponseEntity.ok(toDto(t)))
				.orElse(ResponseEntity.notFound().build());
	}

	@PostMapping
	public ResponseEntity<EventTypeDto> create(@RequestBody EventTypeDto dto) {
		if (dto.name() == null || dto.name().isBlank())
			return ResponseEntity.badRequest().build();
		String key = (dto.key() != null && !dto.key().isBlank()) ? slug(dto.key()) : slug(dto.name());
		if (repo.findByKeyIgnoreCase(key).isPresent())
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		EventType t = new EventType();
		t.setKey(key);
		apply(t, dto);
		if (dto.ordinal() == 0)
			t.setOrdinal(repo.count() == 0 ? 0 : 999); // new custom types sort last by default
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(repo.save(t)));
	}

	@PutMapping("/{id}")
	public ResponseEntity<EventTypeDto> update(@PathVariable("id") String id, @RequestBody EventTypeDto dto) {
		return repo.findById(id).map(t -> {
			apply(t, dto);
			return ResponseEntity.ok(toDto(repo.save(t)));
		}).orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Delete a type. Built-ins (e.g. WEATHER_RAIN, which the importer emits) are
	 * protected → 409. Existing events keep their stored key regardless.
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		EventType t = repo.findById(id).orElse(null);
		if (t == null)
			return ResponseEntity.noContent().build();
		if (t.isBuiltin())
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		repo.delete(t);
		return ResponseEntity.noContent().build();
	}

	private static void apply(EventType t, EventTypeDto dto) {
		if (dto.name() != null && !dto.name().isBlank())
			t.setName(dto.name().trim());
		t.setIcon(dto.icon());
		t.setColor(dto.color());
		t.setOrdinal(dto.ordinal());
		t.setEnabled(dto.enabled());
	}

	private static EventTypeDto toDto(EventType t) {
		return new EventTypeDto(t.getId(), t.getKey(), t.getName(), t.getIcon(), t.getColor(), t.getOrdinal(),
				t.isEnabled(), t.isBuiltin());
	}

	/** UPPER_SNAKE key (matches the built-in convention, e.g. WEATHER_RAIN). */
	private static String slug(String v) {
		return v.trim().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
	}
}
