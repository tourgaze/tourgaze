/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import io.github.tourgaze.dto.TagDto;
import io.github.tourgaze.dto.TagImpactDto;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.FilterPresetRepository;
import io.github.tourgaze.repository.TagRepository;

@RestController
@RequestMapping("/api/tags")
public class TagController {

	/** Cap on descendant names returned for display; the count is always exact. */
	private static final int MAX_NAMES = 50;

	private final TagRepository tagRepo;
	private final ActivityRepository activityRepo;
	private final FilterPresetRepository presetRepo;
	private final io.github.tourgaze.service.mapper.TagMapper tagMapper;

	public TagController(TagRepository tagRepo,
			ActivityRepository activityRepo,
			FilterPresetRepository presetRepo,
			io.github.tourgaze.service.mapper.TagMapper tagMapper) {
		this.tagRepo = tagRepo;
		this.activityRepo = activityRepo;
		this.presetRepo = presetRepo;
		this.tagMapper = tagMapper;
	}

	@GetMapping
	public List<TagDto> list() {
		return tagRepo.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
	}

	@PostMapping
	public ResponseEntity<TagDto> create(@RequestBody TagDto dto) {
		if (dto.name() == null || dto.name().isBlank()) {
			return ResponseEntity.badRequest().build();
		}
		Tag t = new Tag();
		t.setName(dto.name().trim());
		t.setColor(dto.color());
		t.setIcon(dto.icon());
		if (dto.parentId() != null && !dto.parentId().isBlank()) {
			t.setParent(tagRepo.findById(dto.parentId())
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown parentId")));
		}
		t = tagRepo.save(t);
		return ResponseEntity.status(HttpStatus.CREATED).body(toDto(t));
	}

	@PutMapping("/{id}")
	public TagDto update(@PathVariable("id") String id, @RequestBody TagDto dto) {
		Tag t = tagRepo.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found"));
		if (dto.name() != null && !dto.name().isBlank())
			t.setName(dto.name().trim());
		t.setColor(dto.color());
		t.setIcon(dto.icon());
		if (dto.parentId() == null || dto.parentId().isBlank()) {
			t.setParent(null);
		} else if (!dto.parentId().equals(id)) { // refuse self-parent
			t.setParent(tagRepo.findById(dto.parentId())
					.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown parentId")));
		}
		return toDto(tagRepo.save(t));
	}

	/**
	 * What deleting this tag would take down — shown to the user before the
	 * (cascading) delete is confirmed. Counts are authoritative (server-side
	 * over the whole subtree); names are capped for display.
	 */
	@GetMapping("/{id}/impact")
	public ResponseEntity<TagImpactDto> impact(@PathVariable("id") String id) {
		List<Tag> all = tagRepo.findAll();
		Tag self = all.stream().filter(t -> t.getId().equals(id)).findFirst().orElse(null);
		if (self == null)
			return ResponseEntity.notFound().build();

		// children-by-parent, then DFS to collect the full subtree (incl. self).
		Map<String, List<Tag>> byParent = new HashMap<>();
		for (Tag t : all) {
			String pid = t.getParent() != null ? t.getParent().getId() : null;
			if (pid != null)
				byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(t);
		}
		Set<String> subtree = new LinkedHashSet<>();
		List<String> descendantNames = new ArrayList<>();
		ArrayList<Tag> stack = new ArrayList<>(List.of(self));
		while (!stack.isEmpty()) {
			Tag cur = stack.remove(stack.size() - 1);
			if (!subtree.add(cur.getId()))
				continue;
			for (Tag child : byParent.getOrDefault(cur.getId(), List.of())) {
				if (descendantNames.size() < MAX_NAMES)
					descendantNames.add(child.getName());
				stack.add(child);
			}
		}

		long activities = activityRepo.countDistinctByTagIdIn(subtree);
		long presets = presetRepo.countByGroupTagIdIn(subtree);
		return ResponseEntity.ok(new TagImpactDto(
				self.getId(), self.getName(),
				subtree.size() - 1, // exclude self → number of descendant tags
				descendantNames,
				activities, presets));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		tagRepo.deleteById(id);
		return ResponseEntity.noContent().build();
	}

	private TagDto toDto(Tag t) {
		return tagMapper.toDto(t);
	}
}
