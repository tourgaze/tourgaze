/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.github.tourgaze.dto.InboxImportRequest;
import io.github.tourgaze.dto.InboxItemDto;
import io.github.tourgaze.dto.PredictionDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.service.InboxService;
import io.github.tourgaze.service.PredictionService;
import io.github.tourgaze.store.StorageService;

/**
 * Inbox API:
 * POST /api/inbox — upload a file into the inbox (multipart)
 * GET /api/inbox — list pending files (parsed metadata)
 * POST /api/inbox/{name}/import — confirm + create Activity
 * DELETE /api/inbox/{name} — discard
 */
@RestController
@RequestMapping("/api/inbox")
public class InboxController {

	private static final Logger log = LoggerFactory.getLogger(InboxController.class);

	private final StorageService storage;
	private final InboxService inboxService;
	private final PredictionService predictionService;
	private final io.github.tourgaze.service.WatchFolderScanService watchFolders;
	private final io.github.tourgaze.service.InboxStreamService inboxStream;

	public InboxController(StorageService storage, InboxService inboxService, PredictionService predictionService,
			io.github.tourgaze.service.WatchFolderScanService watchFolders,
			io.github.tourgaze.service.InboxStreamService inboxStream) {
		this.storage = storage;
		this.inboxService = inboxService;
		this.predictionService = predictionService;
		this.watchFolders = watchFolders;
		this.inboxStream = inboxStream;
	}

	/**
	 * Live inbox stream (Server-Sent Events). The UI subscribes once and refetches
	 * on each {@code inbox-changed} event instead of polling. Replaces the old 5s
	 * poll (matros push pattern). The event contract is published in OpenAPI via
	 * {@link io.github.tourgaze.enums.InboxStreamEvent} so the client derives the
	 * event names from the generated type (api-first).
	 */
	@io.swagger.v3.oas.annotations.Operation(summary = "Inbox event stream (SSE)", description = "Server-Sent Events stream. Emits a `connected` handshake, then an `inbox-changed` event whenever the inbox changes (file staged, proposal warmed, imported, ignored). The client refetches GET /api/inbox on `inbox-changed`. Event names are the InboxStreamEvent enum values.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "text/event-stream of inbox events", content = @io.swagger.v3.oas.annotations.media.Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = io.github.tourgaze.enums.InboxStreamEvent.class)))
	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream() {
		return inboxStream.subscribe();
	}

	/**
	 * Scan the configured watch folders now (copies new files in); returns the
	 * count.
	 */
	@PostMapping("/scan-watch-folders")
	public Map<String, Integer> scanWatchFolders() {
		return Map.of("copied", watchFolders.scanNow());
	}

	/**
	 * Files the last watch-folder scan skipped because they're already in the
	 * repository (so they're not staged). Read-only; powers the inbox "Already
	 * imported" filter so silent skipping isn't opaque.
	 */
	@GetMapping("/skipped")
	public java.util.List<InboxService.SkippedEntry> skipped() {
		return inboxService.skippedEntries();
	}

	/** Recompute inbox proposals (gear/type/duplicate) from current history. */
	@PostMapping("/refresh")
	public ResponseEntity<Void> refresh() {
		inboxService.refreshProposals();
		return ResponseEntity.noContent().build();
	}

	/** Recompute proposals for a single inbox file. */
	@PostMapping("/{filename}/refresh")
	public ResponseEntity<Void> refreshOne(@PathVariable("filename") String filename) {
		inboxService.refreshProposal(filename);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Free-text place autocomplete (Nominatim forward geocode). Used by the
	 * Start / End location inputs in the AddTour + EditTour forms — as the
	 * user types, we fetch the top matches and surface them in a dropdown.
	 * Caps results at 8; query needs at least 2 chars to fire.
	 */
	@GetMapping("/search-place")
	public java.util.List<io.github.tourgaze.service.PredictionService.PlaceProposal> searchPlace(
			@RequestParam("q") String q) {
		return predictionService.searchPlaces(q, predictionService.currentLanguage(), 8);
	}

	/**
	 * Enrichment lookup for a staged inbox item — reverse-geocodes the start
	 * and end points (in the user's language setting) and proposes tags lifted
	 * from nearby existing rides. The AddTour panel calls this when the user
	 * picks an item, then pre-fills its fields from the response.
	 *
	 * No file lookup needed; the frontend already has the parsed lat/lon from
	 * the inbox list. This endpoint takes them as query params so we don't
	 * have to re-read the FIT just to enrich.
	 */
	@GetMapping("/predict")
	public PredictionDto predict(
			@RequestParam("startLat") double startLat,
			@RequestParam("startLon") double startLon,
			@RequestParam(value = "endLat", required = false) Double endLat,
			@RequestParam(value = "endLon", required = false) Double endLon,
			@RequestParam(value = "distanceKm", required = false) Double distanceKm) {
		return predictionService.predict(startLat, startLon, endLat, endLon, distanceKm);
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
		String original = file.getOriginalFilename();
		if (file.isEmpty() || original == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "Empty file"));
		}
		String lower = original.toLowerCase(Locale.ROOT);
		int extDot = lower.lastIndexOf('.');
		String uploadExt = extDot >= 0 ? lower.substring(extDot + 1) : "";
		if (io.github.tourgaze.parser.SourceFormat.from(uploadExt) == null) {
			return ResponseEntity.badRequest().body(Map.of(
					"error",
					"Only ." + String.join("/.", io.github.tourgaze.parser.SourceFormat.extensions()) + " accepted"));
		}

		// Preserve the original filename. The UUID-prefix scheme we used to
		// do made the inbox unreadable ("b315be00-..._2014-07-11-09-55-10"
		// instead of "2014-07-11-09-55-10.fit"). Collisions get a numeric
		// suffix instead — and most collisions are exact-content duplicates,
		// which the next list() call will detect via SHA-256 and sweep away.
		String basename = Path.of(original).getFileName().toString();
		Path dest = storage.inboxDir().resolve(basename);
		int n = 1;
		int maxAttempts = 100;
		while (Files.exists(dest) && n < maxAttempts) {
			int dot = basename.lastIndexOf('.');
			String stem = dot > 0 ? basename.substring(0, dot) : basename;
			String ext = dot > 0 ? basename.substring(dot) : "";
			dest = storage.inboxDir().resolve(stem + "-" + n + ext);
			n++;
		}
		String safeName = dest.getFileName().toString();
		try {
			file.transferTo(dest);
		} catch (IOException e) {
			log.error("Could not write {}: {}", safeName, e.getMessage());
			return ResponseEntity.internalServerError().body(Map.of("error", "Could not write to inbox"));
		}
		// Push an inbox-changed event so the new card shows immediately; the warm job
		// fills in its proposal (reverse-geocode + tag vote) on its next tick.
		inboxService.notifyChanged();
		return ResponseEntity.accepted().body(Map.of("status", "staged", "inboxName", safeName));
	}

	@GetMapping
	public ResponseEntity<List<InboxItemDto>> list() {
		try {
			return ResponseEntity.ok(inboxService.listPending());
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	@PostMapping("/{filename}/import")
	public ResponseEntity<?> importItem(@PathVariable("filename") String filename,
			@RequestBody(required = false) InboxImportRequest req) {
		try {
			Activity a = inboxService.importItem(filename, req);
			inboxService.notifyChanged(); // item left the inbox → refresh subscribers
			return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("activityId", a.getId()));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
		}
	}

	/** Route preview (downsampled lat/lon) for a still-staged inbox file. */
	@GetMapping("/{filename}/track")
	public ResponseEntity<List<InboxService.PreviewPoint>> track(@PathVariable("filename") String filename) {
		try {
			List<InboxService.PreviewPoint> pts = inboxService.trackPreview(filename);
			return pts == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(pts);
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	// ── Media (photos) staged against this inbox file, moved to the ride on import
	// ──
	@PostMapping(value = "/{filename}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<List<String>> uploadMedia(@PathVariable("filename") String filename,
			@RequestParam("files") MultipartFile[] files) {
		try {
			return ResponseEntity.ok(inboxService.stageMedia(filename, files));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	@GetMapping("/{filename}/media")
	public ResponseEntity<List<String>> listMedia(@PathVariable("filename") String filename) {
		try {
			return ResponseEntity.ok(inboxService.listStagedMedia(filename));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	@GetMapping("/{filename}/media/{name}")
	public ResponseEntity<byte[]> getMedia(@PathVariable("filename") String filename,
			@PathVariable("name") String name) {
		return serveImage(inboxService.stagedMediaFile(filename, name));
	}

	@DeleteMapping("/{filename}/media/{name}")
	public ResponseEntity<Void> deleteMedia(@PathVariable("filename") String filename,
			@PathVariable("name") String name) {
		try {
			inboxService.deleteStagedMedia(filename, name);
			return ResponseEntity.noContent().build();
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	static ResponseEntity<byte[]> serveImage(Path p) {
		if (p == null)
			return ResponseEntity.notFound().build();
		try {
			byte[] bytes = Files.readAllBytes(p);
			String ct = Files.probeContentType(p);
			return ResponseEntity.ok()
					.contentType(MediaType.parseMediaType(ct != null ? ct : "application/octet-stream"))
					.header("Cache-Control", "public, max-age=86400")
					.body(bytes);
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Serve already-read bytes, deriving the content type from {@code name} (the
	 * logical filename) — used for encrypted media where the on-disk file is a
	 * {@code .enc} blob, so the extension must come from the logical name.
	 */
	static ResponseEntity<byte[]> serveImageBytes(byte[] bytes, String name) {
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(contentTypeForName(name)))
				.header("Cache-Control", "public, max-age=86400")
				.body(bytes);
	}

	private static String contentTypeForName(String name) {
		String n = name.toLowerCase();
		if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
			return "image/jpeg";
		if (n.endsWith(".png"))
			return "image/png";
		if (n.endsWith(".gif"))
			return "image/gif";
		if (n.endsWith(".webp"))
			return "image/webp";
		if (n.endsWith(".mp4"))
			return "video/mp4";
		if (n.endsWith(".mov"))
			return "video/quicktime";
		if (n.endsWith(".webm"))
			return "video/webm";
		String guess = java.net.URLConnection.guessContentTypeFromName(name);
		return guess != null ? guess : "application/octet-stream";
	}

	@DeleteMapping("/{filename}")
	public ResponseEntity<Void> discard(@PathVariable("filename") String filename) {
		try {
			inboxService.discard(filename);
			inboxService.notifyChanged();
			return ResponseEntity.noContent().build();
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/** Archive a staged file to inbox-processed/ without importing it. */
	@PostMapping("/{filename}/processed")
	public ResponseEntity<Void> moveToProcessed(@PathVariable("filename") String filename) {
		try {
			inboxService.moveToProcessed(filename);
			inboxService.notifyChanged();
			return ResponseEntity.noContent().build();
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}
}
