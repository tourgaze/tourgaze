/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.github.tourgaze.dto.UserDto;
import io.github.tourgaze.entity.User;
import io.github.tourgaze.repository.UserRepository;

@RestController
@RequestMapping("/api/users")
public class UserController {

	private final UserRepository userRepo;
	private final io.github.tourgaze.service.mapper.UserMapper userMapper;
	private final io.github.tourgaze.service.RideExportService rideExport;

	public UserController(UserRepository userRepo, io.github.tourgaze.service.mapper.UserMapper userMapper,
			io.github.tourgaze.service.RideExportService rideExport) {
		this.userRepo = userRepo;
		this.userMapper = userMapper;
		this.rideExport = rideExport;
	}

	/** Frontend uses this to detect "empty DB → run first-time setup". */
	@GetMapping("/status")
	public Map<String, Object> status() {
		long count = userRepo.count();
		return Map.of("count", count, "initialized", count > 0);
	}

	@GetMapping
	public List<UserDto> getAll() {
		return userRepo.findAll().stream().map(this::toDto).toList();
	}

	@PostMapping
	public ResponseEntity<UserDto> create(@RequestBody UserDto dto) {
		if (dto.username() == null || dto.username().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		User u = new User();
		applyDto(u, dto);
		u = userRepo.save(u);
		rideExport.exportLibraryAsync(); // keep the recovery library sidecar fresh
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(u));
	}

	@PutMapping("/{id}")
	public ResponseEntity<UserDto> update(@PathVariable("id") String id, @RequestBody UserDto dto) {
		return userRepo.findById(id).map(u -> {
			applyDto(u, dto);
			u = userRepo.save(u);
			rideExport.exportLibraryAsync(); // keep the recovery library sidecar fresh
			return ResponseEntity.ok(toDto(u));
		}).orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		userRepo.deleteById(id);
		rideExport.exportLibraryAsync(); // keep the recovery library sidecar fresh
		return ResponseEntity.noContent().build();
	}

	private void applyDto(User u, UserDto dto) {
		if (dto.username() != null && !dto.username().isBlank())
			u.setUsername(dto.username());
		if (dto.displayName() != null)
			u.setDisplayName(dto.displayName());
		u.setDateOfBirth(dto.dateOfBirth());
		u.setHeightCm(dto.heightCm());
		u.setWeightKg(dto.weightKg());
		u.setGender(dto.gender());
		u.setRestingHr(dto.restingHr());
		u.setMaxHr(dto.maxHr());
	}

	private UserDto toDto(User u) {
		return userMapper.toDto(u);
	}
}
