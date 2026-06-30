/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.parser;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Format-agnostic entry point that turns a ride file's bytes into a
 * {@link ParseResult}. Spring injects every {@link TrackFileParser} provider;
 * this picks the one whose {@link TrackFileParser#supports(String)} matches —
 * so import, the on-demand track cache, and export contain no format
 * conditionals, and a new format is added purely by registering a provider.
 */
@Component
public class TrackParser {

	private final List<TrackFileParser> providers;

	public TrackParser(List<TrackFileParser> providers) {
		this.providers = providers;
	}

	/** True if some provider can parse this format/extension. */
	public boolean canParse(String format) {
		return provider(format).isPresent();
	}

	public ParseResult parse(byte[] data, String format) {
		ParseResult raw = provider(format)
				.orElseThrow(() -> new IllegalArgumentException("No parser for format: " + format))
				.parse(data);
		// Repair GPS pre-lock clock garbage (sentinel ~1999 timestamps before the
		// fix locks) centrally, so import, the track cache and export agree. No-op
		// for clean files.
		return TrackTimeSanitizer.sanitize(raw);
	}

	/** Convenience: pick the provider from a filename's extension. */
	public ParseResult parseByFilename(byte[] data, String filename) {
		return parse(data, extensionOf(filename));
	}

	/**
	 * Photos embedded in the file for this format (empty unless the provider has
	 * any).
	 */
	public List<EmbeddedPhoto> extractPhotos(byte[] data, String format) {
		return provider(format).map(p -> p.extractPhotos(data)).orElse(List.of());
	}

	private Optional<TrackFileParser> provider(String format) {
		if (format == null)
			return Optional.empty();
		return providers.stream().filter(p -> p.supports(format)).findFirst();
	}

	private static String extensionOf(String filename) {
		if (filename == null)
			return "";
		int dot = filename.lastIndexOf('.');
		return dot >= 0 ? filename.substring(dot + 1).toLowerCase() : "";
	}
}
