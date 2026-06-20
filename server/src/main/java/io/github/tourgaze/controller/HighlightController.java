/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.tourgaze.entity.GeoFeature;
import io.github.tourgaze.entity.Setting;
import io.github.tourgaze.repository.SettingRepository;
import io.github.tourgaze.service.PeakPassService;
import io.github.tourgaze.service.PeakPassService.Match;

/**
 * Auto-detected ride highlights — mountain passes crossed and named peaks
 * nearby, from cached OSM data. Names come back localized to the user's
 * {@code app.language} setting (falling back to the default OSM name).
 */
@RestController
@RequestMapping("/api/activities")
public class HighlightController {

	private final PeakPassService peakPass;
	private final SettingRepository settingRepo;

	public HighlightController(PeakPassService peakPass, SettingRepository settingRepo) {
		this.peakPass = peakPass;
		this.settingRepo = settingRepo;
	}

	public record HighlightDto(long osmId, String type, String name, Double eleM,
			double lat, double lon, double distM, double trackDistKm,
			boolean summited, String wikidata) {
	}

	public record HighlightsDto(List<HighlightDto> passes, List<HighlightDto> peaks) {
	}

	@GetMapping("/{id}/highlights")
	public HighlightsDto get(@PathVariable("id") String id) {
		String lang = settingRepo.findById("app.language").map(Setting::getValue).orElse("de");
		PeakPassService.Highlights h = peakPass.computeHighlights(id);
		HighlightsDto dto = new HighlightsDto(map(h.passes(), lang), map(h.peaks(), lang));
		// On-view fallback for rides imported before this feature existed (or
		// before their region was covered): warm the region in the background so
		// a later view has highlights. No-op for already-covered regions.
		if (dto.passes().isEmpty() && dto.peaks().isEmpty())
			peakPass.ensureRegionsForActivityAsync(id);
		return dto;
	}

	private List<HighlightDto> map(List<Match> matches, String lang) {
		return matches.stream().map(m -> {
			GeoFeature f = m.feature();
			return new HighlightDto(
					f.getOsmId(), f.getType(), localizedName(f, lang), f.getEleM(),
					f.getLat(), f.getLon(),
					Math.round(m.distM()), Math.round(m.trackDistKm() * 100) / 100.0,
					m.summited(), f.getWikidata());
		}).toList();
	}

	/** name:<lang> if we have it, else the default OSM name, else any variant. */
	private static String localizedName(GeoFeature f, String lang) {
		String localized = "en".equals(lang) ? f.getNameEn() : "de".equals(lang) ? f.getNameDe() : null;
		if (localized != null && !localized.isBlank())
			return localized;
		if (f.getName() != null && !f.getName().isBlank())
			return f.getName();
		if (f.getNameEn() != null)
			return f.getNameEn();
		return f.getNameDe();
	}
}
