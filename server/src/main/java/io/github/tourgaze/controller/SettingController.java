/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import io.github.tourgaze.dto.SettingDto;
import io.github.tourgaze.entity.Setting;
import io.github.tourgaze.repository.SettingRepository;

@RestController
@RequestMapping("/api/settings")
public class SettingController {

	private final SettingRepository settingRepo;

	public SettingController(SettingRepository settingRepo) {
		this.settingRepo = settingRepo;
	}

	@GetMapping
	@Cacheable("settings")
	public List<SettingDto> getAll() {
		return settingRepo.findAll().stream()
				.map(s -> new SettingDto(s.getKey(), s.getValue()))
				.toList();
	}

	@PutMapping("/{key}")
	@CacheEvict(value = "settings", allEntries = true)
	public ResponseEntity<SettingDto> update(@PathVariable("key") String key,
			@RequestBody SettingDto dto) {
		Setting setting = settingRepo.findById(key).orElse(new Setting(key, null));
		setting.setValue(dto.value());
		settingRepo.save(setting);
		return ResponseEntity.ok(new SettingDto(setting.getKey(), setting.getValue()));
	}
}
