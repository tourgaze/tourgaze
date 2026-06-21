/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.Comparator;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.github.tourgaze.dto.GearDto;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.repository.UserRepository;

@RestController
@RequestMapping("/api/gear")
public class GearController {

	private final GearRepository gearRepo;
	private final UserRepository userRepo;
	private final io.github.tourgaze.service.mapper.GearMapper gearMapper;

	public GearController(GearRepository gearRepo, UserRepository userRepo,
			io.github.tourgaze.service.mapper.GearMapper gearMapper) {
		this.gearRepo = gearRepo;
		this.userRepo = userRepo;
		this.gearMapper = gearMapper;
	}

	/** All gear, name-sorted. Optionally narrowed to one rider via ?userId=. */
	@GetMapping
	public List<GearDto> getAll(@RequestParam(value = "userId", required = false) String userId) {
		List<Gear> gear = (userId != null && !userId.isBlank())
				? gearRepo.findByUserIdOrderByNameAsc(userId)
				: gearRepo.findAll();
		return gear.stream()
				.sorted(Comparator.comparing(Gear::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
				.map(this::toDto)
				.toList();
	}

	@PostMapping
	public ResponseEntity<GearDto> create(@RequestBody GearDto dto) {
		if (dto.name() == null || dto.name().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		Gear g = new Gear();
		applyDto(g, dto);
		g = gearRepo.save(g);
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(g));
	}

	@PutMapping("/{id}")
	public ResponseEntity<GearDto> update(@PathVariable("id") String id, @RequestBody GearDto dto) {
		return gearRepo.findById(id).map(g -> {
			applyDto(g, dto);
			g = gearRepo.save(g);
			return ResponseEntity.ok(toDto(g));
		}).orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		gearRepo.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	private void applyDto(Gear g, GearDto dto) {
		if (dto.name() != null && !dto.name().isBlank())
			g.setName(dto.name().trim());
		g.setType(dto.type());
		g.setDescription(dto.description());
		g.setIcon(dto.icon());
		g.setRetiredAt(dto.retiredAt());
		// Owner is optional; clear it when the DTO omits it, otherwise resolve the
		// user.
		if (dto.userId() != null && !dto.userId().isBlank()) {
			userRepo.findById(dto.userId()).ifPresent(g::setUser);
		} else {
			g.setUser(null);
		}
	}

	private GearDto toDto(Gear g) {
		return gearMapper.toDto(g);
	}
}
