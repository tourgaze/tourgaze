/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.InboxImportRequest;
import io.github.tourgaze.dto.InboxItemDto;
import io.github.tourgaze.dto.PredictionDto;
import io.github.tourgaze.dto.RouteCandidate;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.entity.User;
import io.github.tourgaze.parser.EmbeddedPhoto;
import io.github.tourgaze.parser.ParseResult;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.repository.TagRepository;
import io.github.tourgaze.repository.UserRepository;
import io.github.tourgaze.store.StorageService;

/**
 * Inbox staging — files dropped into inbox/ are parsed on demand for a review
 * UI; nothing hits the activity table until the user clicks "Import" with a
 * filled-in form. Mirrors the matrosdms InboxFile model (transient, no DB row).
 */
@Service
public class InboxService {

	private static final Logger log = LoggerFactory.getLogger(InboxService.class);

	/**
	 * Cell-overlap (Jaccard) at/above which an import is treated as the same track.
	 */
	private static final double SAME_TRACK_OVERLAP = 0.80;

	/**
	 * Proposal cache keyed by file content hash — so the inbox list doesn't
	 * re-reverse-geocode / re-vote tags for the same staged file on every request.
	 * Rebuilt cheaply on boot: reverse-geocoding is disk-cached, so the warm job
	 * mostly replays cache hits.
	 */
	private final ConcurrentHashMap<String, PredictionDto> predictionCache = new ConcurrentHashMap<>();

	/**
	 * Parsed-metadata cache keyed by filename → the file's decoded card (distance,
	 * type, duplicate, …) plus its mtime. The warm job fills this;
	 * {@code GET /inbox}
	 * reads it instead of re-parsing every file on every request — so the list is a
	 * fast directory listing, and uncached files come back as skeletons until
	 * warmed.
	 */
	private final ConcurrentHashMap<String, ParsedCard> parseCache = new ConcurrentHashMap<>();

	/** Decoded inbox-file metadata (everything except the geocode proposal). */
	private record ParsedCard(long mtime, String sha, long size,
			io.github.tourgaze.parser.SourceFormat format, String suggestedName,
			io.github.tourgaze.enums.ActivityType type, Instant startTime, Double distanceKm, Integer durationS,
			Double lat, Double lon, Double endLat, Double endLon,
			String existingActivityId, String gearId, String gearName,
			String dupId, String dupName,
			boolean hasHr, boolean hasCadence, boolean hasPower) {
	}

	/**
	 * Filename → originating watch-folder label, for files copied in by the scanner
	 * (Settings → Inbox source). Persisted in cache/inbox-origins.json (app state
	 * never lives inside an inbox/watch folder) so the card can show where a ride
	 * came from even after a restart. Hand-dropped/uploaded files have no entry.
	 */
	private final ConcurrentHashMap<String, String> origins = new ConcurrentHashMap<>();

	/**
	 * Filename → device source path, recorded only for delete-from-device sources
	 * so import (or "Delete from device") can remove the original AFTER the store
	 * copy succeeds — never before. Persisted in cache/inbox-sources.json so it
	 * survives a restart between staging and import.
	 */
	private final ConcurrentHashMap<String, String> sourcePaths = new ConcurrentHashMap<>();

	/**
	 * What the last watch-folder scan skipped because the file is already in the
	 * repository on a keep-on-device source — surfaced read-only by the inbox
	 * "Already imported" (Ignored) filter so silent skipping isn't opaque.
	 */
	public record SkippedEntry(String filename, String sourceLabel, String activityId) {
	}

	private final java.util.concurrent.ConcurrentLinkedDeque<SkippedEntry> skipped = new java.util.concurrent.ConcurrentLinkedDeque<>();

	/** When the warm job last completed a sweep (diagnostics). */
	private volatile Instant lastWarmRun;

	private final StorageService storage;
	private final io.github.tourgaze.parser.TrackParser trackParser;
	private final ActivityRepository activityRepo;
	private final GearRepository gearRepo;
	private final TagRepository tagRepo;
	private final UserRepository userRepo;
	private final WeatherService weatherService;
	private final TrackCacheService trackCache;
	private final TileWarmerService tileWarmer;
	private final PredictionService predictionService;
	private final org.springframework.context.ApplicationEventPublisher events;
	private final MediaManifestService mediaManifest;
	private final MediaProcessor mediaProcessor;
	private final RideProposalService proposalService;
	private final PeakPassService peakPass;
	private final InboxStreamService inboxStream;
	private final ObjectMapper objectMapper;

	public InboxService(StorageService storage,
			io.github.tourgaze.parser.TrackParser trackParser,
			ActivityRepository activityRepo,
			GearRepository gearRepo,
			TagRepository tagRepo,
			UserRepository userRepo,
			WeatherService weatherService,
			TrackCacheService trackCache,
			TileWarmerService tileWarmer,
			PredictionService predictionService,
			org.springframework.context.ApplicationEventPublisher events,
			MediaManifestService mediaManifest,
			MediaProcessor mediaProcessor,
			RideProposalService proposalService,
			PeakPassService peakPass,
			InboxStreamService inboxStream,
			ObjectMapper objectMapper) {
		this.storage = storage;
		this.trackParser = trackParser;
		this.activityRepo = activityRepo;
		this.gearRepo = gearRepo;
		this.tagRepo = tagRepo;
		this.userRepo = userRepo;
		this.weatherService = weatherService;
		this.trackCache = trackCache;
		this.tileWarmer = tileWarmer;
		this.predictionService = predictionService;
		this.events = events;
		this.mediaManifest = mediaManifest;
		this.mediaProcessor = mediaProcessor;
		this.proposalService = proposalService;
		this.peakPass = peakPass;
		this.inboxStream = inboxStream;
		this.objectMapper = objectMapper;
	}

	private Path originsFile() {
		// App state lives in cache/, never inside an inbox/watch folder.
		return storage.cacheDir().resolve("inbox-origins.json");
	}

	/** Load the filename → watch-folder-label map once at startup (best-effort). */
	@PostConstruct
	void loadOrigins() {
		Path f = originsFile();
		// One-time migration: earlier builds wrote this as a dotfile inside inbox/.
		// App state must never live in an inbox/watch folder, so relocate it.
		Path legacy = storage.inboxDir().resolve(".origins.json");
		if (Files.isRegularFile(legacy)) {
			try {
				if (!Files.isRegularFile(f)) {
					Files.createDirectories(f.getParent());
					Files.move(legacy, f);
				} else {
					Files.deleteIfExists(legacy);
				}
			} catch (IOException e) {
				log.debug("[Inbox] origins migration skipped: {}", e.getMessage());
			}
		}
		if (!Files.isRegularFile(f))
			return;
		try {
			origins.putAll(objectMapper.readValue(Files.readAllBytes(f),
					new TypeReference<Map<String, String>>() {
					}));
		} catch (Exception e) {
			log.debug("[Inbox] origins load skipped: {}", e.getMessage());
		}
	}

	private synchronized void saveOrigins() {
		Path f = originsFile();
		try {
			Files.createDirectories(f.getParent());
			Files.write(f, objectMapper.writeValueAsBytes(origins));
		} catch (IOException e) {
			log.debug("[Inbox] origins save failed: {}", e.getMessage());
		}
	}

	/** Record which watch-folder a freshly-copied inbox file came from. */
	public void recordOrigin(String filename, String label) {
		if (filename == null || label == null || label.isBlank())
			return;
		origins.put(filename, label);
		saveOrigins();
	}

	private Path sourcesFile() {
		return storage.cacheDir().resolve("inbox-sources.json");
	}

	/**
	 * One-time migration: the old inbox-processed/ archive is gone ("delete from
	 * inbox" now uses the single inbox-ignored/). Fold any files it still holds
	 * into inbox-ignored/ so their hashes stay parked (won't re-stage), then drop
	 * the dead folder. Best-effort; safe to run every boot.
	 */
	@PostConstruct
	void migrateProcessedIntoIgnored() {
		Path processed = storage.inboxDir().resolveSibling("inbox-processed");
		if (!Files.isDirectory(processed))
			return;
		Path ignored = storage.inboxIgnoredDir();
		try {
			Files.createDirectories(ignored);
			try (var s = Files.list(processed)) {
				for (Path p : (Iterable<Path>) s::iterator) {
					if (!Files.isRegularFile(p))
						continue;
					String name = p.getFileName().toString();
					Path dest = ignored.resolve(name);
					int n = 1;
					while (Files.exists(dest) && n < 100) {
						int dot = name.lastIndexOf('.');
						String stem = dot > 0 ? name.substring(0, dot) : name;
						String ext = dot > 0 ? name.substring(dot) : "";
						dest = ignored.resolve(stem + "-" + n + ext);
						n++;
					}
					Files.move(p, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			}
			Files.deleteIfExists(processed);
			log.info("[Inbox] migrated legacy inbox-processed/ into inbox-ignored/");
		} catch (IOException e) {
			log.debug("[Inbox] processed→ignored migration skipped: {}", e.getMessage());
		}
	}

	@PostConstruct
	void loadSources() {
		Path f = sourcesFile();
		if (!Files.isRegularFile(f))
			return;
		try {
			sourcePaths.putAll(objectMapper.readValue(Files.readAllBytes(f),
					new TypeReference<Map<String, String>>() {
					}));
		} catch (Exception e) {
			log.debug("[Inbox] sources load skipped: {}", e.getMessage());
		}
	}

	private synchronized void saveSources() {
		Path f = sourcesFile();
		try {
			Files.createDirectories(f.getParent());
			Files.write(f, objectMapper.writeValueAsBytes(sourcePaths));
		} catch (IOException e) {
			log.debug("[Inbox] sources save failed: {}", e.getMessage());
		}
	}

	/**
	 * Remember the device original a staged file came from, so import (or "Delete
	 * from device") can delete it after the store copy. Only called for
	 * delete-from-device sources; keep-on-device files record nothing.
	 */
	public void recordSource(String filename, Path sourcePath) {
		if (filename == null || sourcePath == null)
			return;
		sourcePaths.put(filename, sourcePath.toString());
		saveSources();
	}

	/**
	 * Delete the recorded device original for a staged file (best-effort), then
	 * forget the mapping. Safe to call when nothing was recorded (no-op). Only
	 * ever invoked AFTER the file is safely in {@code store/}.
	 */
	private void deleteRecordedSource(String filename) {
		String p = sourcePaths.remove(filename);
		if (p == null)
			return;
		saveSources();
		try {
			if (Files.deleteIfExists(Path.of(p)))
				log.info("[Inbox] removed device original {} after import of {}", p, filename);
		} catch (Exception e) {
			log.warn("[Inbox] could not remove device original {}: {}", p, e.getMessage());
		}
	}

	/**
	 * The activity id whose source hash matches, if this content is in the repo.
	 */
	public Optional<String> importedActivityId(String sha256) {
		return activityRepo.findBySourceHash(sha256).map(Activity::getId);
	}

	// ── "Already imported" (Ignored) filter: the scan records files it skipped
	// because they're already in the repo. Read-only transparency. ──

	/** Reset before a scan so the list reflects only the current device state. */
	public void clearSkipped() {
		skipped.clear();
	}

	public void recordSkipped(String filename, String sourceLabel, String activityId) {
		skipped.removeIf(s -> s.filename().equals(filename));
		skipped.addFirst(new SkippedEntry(filename, sourceLabel, activityId));
		while (skipped.size() > 500)
			skipped.removeLast();
	}

	public List<SkippedEntry> skippedEntries() {
		return new java.util.ArrayList<>(skipped);
	}

	/**
	 * The one and only inbox warm — a single scheduled job with two phases per run:
	 * first PARSE any new/changed files (fast — fills distance/type/duplicate on
	 * the
	 * cards), then GEOCODE the parsed-but-unwarmed ones (rate-limited — fills place
	 * +
	 * tags). An SSE event is pushed after each batch so the cards enrich
	 * progressively. Items are independent: a geocode failure just leaves one for
	 * the
	 * next run (failsafe, built in).
	 *
	 * No triggers, no single-flight flag: {@code fixedDelay} guarantees runs never
	 * overlap, and parses/geocodes are cached, so a run with nothing new is free.
	 * {@code GET /inbox} reads the parse cache, so it never blocks on parsing.
	 */
	@org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${tourgaze.inbox.warm-ms:10000}", initialDelayString = "${tourgaze.inbox.warm-initial-ms:5000}")
	public void warmPending() {
		int parsed = 0;
		int warmed = 0;
		try {
			List<Path> files;
			try (var stream = Files.list(storage.inboxDir())) {
				files = stream.filter(Files::isRegularFile).filter(this::isSupported).toList();
			}
			// Phase 1 — parse new/changed files (turns skeleton cards into full cards).
			List<RouteCandidate> candidates = null;
			for (Path file : files) {
				String name = file.getFileName().toString();
				long mtime = mtimeOf(file);
				ParsedCard cached = parseCache.get(name);
				if (cached != null && cached.mtime() == mtime)
					continue; // already parsed, unchanged
				if (candidates == null)
					candidates = activityRepo.findRouteCandidates("");
				ParsedCard pc = parseOne(file, mtime, candidates);
				if (pc == null)
					continue; // unreadable
				// Byte-identical to an imported ride: keep the card (with
				// existingActivityId set) so the user SEES it's a duplicate and
				// removes it deliberately — don't silently sweep the inbox copy.
				parseCache.put(name, pc);
				if (++parsed % 10 == 0)
					inboxStream.changed(); // progressive push
			}
			if (parsed > 0)
				inboxStream.changed();
			// Phase 2 — geocode parsed files that aren't warmed yet (rate-limited).
			for (Path file : files) {
				ParsedCard pc = parseCache.get(file.getFileName().toString());
				if (pc == null || pc.sha() == null || pc.lat() == null)
					continue;
				if (predictionCache.containsKey(pc.sha()))
					continue;
				PredictionDto pred = safePredict(pc.lat(), pc.lon(), pc.endLat(), pc.endLon(), pc.distanceKm());
				// Only cache a real result — leave failures uncached so a later run retries.
				if (pred != null && pred.startLocation() != null && !pred.startLocation().isBlank()) {
					predictionCache.put(pc.sha(), pred);
					if (++warmed % 5 == 0)
						inboxStream.changed();
				}
			}
			// Drop cache entries for files that have left the inbox (imported/ignored).
			Set<String> present = files.stream()
					.map(f -> f.getFileName().toString())
					.collect(java.util.stream.Collectors.toSet());
			parseCache.keySet().retainAll(present);
			if (origins.keySet().retainAll(present))
				saveOrigins();
			if (warmed > 0)
				log.info("[Inbox] warmed {} proposal(s)", warmed);
			if (warmed > 0)
				inboxStream.changed();
		} catch (IOException e) {
			log.debug("[Inbox] warm sweep failed: {}", e.getMessage());
		} finally {
			lastWarmRun = Instant.now();
		}
	}

	/** When the warm job last completed a sweep (null until the first run). */
	public Instant lastWarmRun() {
		return lastWarmRun;
	}

	private long mtimeOf(Path f) {
		try {
			return Files.getLastModifiedTime(f).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	/**
	 * Decode one inbox file into its {@link ParsedCard} (distance, type, gear,
	 * duplicate detection, …) — everything the card shows except the geocode
	 * proposal, which Phase 2 fills in. Returns null if the file can't be read; a
	 * non-track file (TCX/unknown) still returns a stub card so it appears in the
	 * UI.
	 */
	private ParsedCard parseOne(Path file, long mtime, List<RouteCandidate> candidates) {
		try {
			String filename = file.getFileName().toString();
			String ext = extensionOf(filename);
			long size = Files.size(file);
			byte[] data = Files.readAllBytes(file);
			String sha = sha256Hex(data);
			var format = io.github.tourgaze.parser.SourceFormat.from(ext);
			String suggestedName = filename.replaceAll("(?i)\\.(fit|gpx|tcx|kmz|kml)$", "");
			// If the same content is already imported, flag it so the warm sweeps it.
			String existingId = activityRepo.findBySourceHash(sha).map(Activity::getId).orElse(null);

			if (existingId == null && trackParser.canParse(ext)) {
				ParseResult r = trackParser.parse(data, ext);
				Double lat = r.points().isEmpty() ? null : r.points().get(0).lat();
				Double lon = r.points().isEmpty() ? null : r.points().get(0).lon();
				int last = r.points().size() - 1;
				Double endLat = last <= 0 ? null : r.points().get(last).lat();
				Double endLon = last <= 0 ? null : r.points().get(last).lon();
				Double distanceKm = r.distanceM() != null ? r.distanceM() / 1000.0 : null;
				Double avgKmh = (r.durationS() != null && r.durationS() > 0 && distanceKm != null)
						? distanceKm / (r.durationS() / 3600.0)
						: (r.avgSpeedMs() != null ? r.avgSpeedMs() * 3.6 : null);
				var type = proposalService.proposeType(r.sport(), avgKmh, distanceKm);
				var gear = proposalService.proposeGear(avgKmh, distanceKm, r.ascentM(), type);
				RouteCandidate dup = findSameTrack(r.points(), candidates);
				return new ParsedCard(mtime, sha, size, format, suggestedName, type, r.startTime(), distanceKm,
						r.durationS(), lat, lon, endLat, endLon, existingId,
						gear != null ? gear.gearId() : null, gear != null ? gear.gearName() : null,
						dup != null ? dup.id() : null, dup != null ? dup.name() : null,
						r.avgHr() != null, r.avgCadence() != null, r.avgPowerW() != null);
			}
			// Already-imported or unparseable format → stub card (no track metadata).
			return new ParsedCard(mtime, sha, size, format, suggestedName, null, null, null, null,
					null, null, null, null, existingId, null, null, null, null, false, false, false);
		} catch (Exception e) {
			log.warn("Could not parse inbox file {}: {}", file, e.getMessage());
			return null;
		}
	}

	/** Push an inbox-changed event now (no warm) — for new files / removals. */
	public void notifyChanged() {
		inboxStream.changed();
	}

	/**
	 * Force proposals to be recomputed: drops the parse cache (where the gear
	 * proposal lives) so the next warm re-derives gear/type/duplicate from the
	 * CURRENT activity history — e.g. after you import a ride with a gear and want
	 * the next items to reflect it. Geocode cache is kept (expensive, unchanged).
	 * The scheduled warm refills shortly; an SSE nudge repaints the list.
	 */
	public void refreshProposals() {
		parseCache.clear();
		inboxStream.changed();
	}

	/**
	 * Recompute proposals for ONE inbox file (gear/type/duplicate + geocode): drop
	 * its cache entries and re-run the warm, which then only re-processes this file
	 * (others stay cached). Fast, single-item version of {@link #refreshProposals}.
	 */
	public void refreshProposal(String filename) {
		ParsedCard pc = parseCache.remove(filename);
		if (pc != null && pc.sha() != null)
			predictionCache.remove(pc.sha());
		warmPending();
		inboxStream.changed();
	}

	/**
	 * Instant inbox listing — just a directory scan (names + size + mtime, no file
	 * reads, no parsing). Files already decoded by the warm job come back as full
	 * cards (assembled from the parse + geocode caches); the rest come back as
	 * skeletons ({@code parsing: true}) and fill in via SSE as the warm processes
	 * them. So the list paints immediately even for a huge inbox.
	 */
	public List<InboxItemDto> listPending() throws IOException {
		List<Path> files;
		try (var stream = Files.list(storage.inboxDir())) {
			files = stream.filter(Files::isRegularFile).filter(this::isSupported).sorted().toList();
		}
		List<InboxItemDto> out = new java.util.ArrayList<>(files.size());
		for (Path file : files) {
			String name = file.getFileName().toString();
			ParsedCard pc = parseCache.get(name);
			if (pc != null && pc.mtime() == mtimeOf(file)) {
				out.add(assemble(name, pc));
			} else {
				out.add(skeleton(file, name));
			}
		}
		return out;
	}

	/**
	 * Assemble a full card from a cached parse + (if ready) its geocode proposal.
	 */
	private InboxItemDto assemble(String filename, ParsedCard pc) {
		PredictionDto pred = pc.sha() != null ? predictionCache.get(pc.sha()) : null;
		// Smart name (location-based) wins over the bare filename stem.
		String displayName = (pred != null && pred.suggestedName() != null && !pred.suggestedName().isBlank())
				? pred.suggestedName()
				: pc.suggestedName();
		// "Processing" once parsed: has GPS but the geocode/tag-vote hasn't run yet.
		boolean proposalPending = pred == null && pc.lat() != null;
		return new InboxItemDto(
				filename, pc.sha(), pc.size(), pc.format(), displayName,
				pc.type() != null ? pc.type().wire() : null,
				pc.startTime(), pc.distanceKm(), pc.durationS(), pc.lat(), pc.lon(), pc.endLat(), pc.endLon(),
				pc.existingActivityId(), pc.gearId(), pc.gearName(), pc.dupId(), pc.dupName(),
				pred != null ? pred.startLocation() : null,
				pred != null ? pred.region() : null,
				pred != null ? pred.country() : null,
				pred != null ? proposedTagNames(pred) : List.of(),
				proposalPending, false, origins.get(filename),
				pc.hasHr(), pc.hasCadence(), pc.hasPower());
	}

	/** A not-yet-parsed card: name/size/format only, {@code parsing: true}. */
	private InboxItemDto skeleton(Path file, String filename) {
		long size;
		try {
			size = Files.size(file);
		} catch (IOException e) {
			size = 0;
		}
		String suggestedName = filename.replaceAll("(?i)\\.(fit|gpx|tcx|kmz|kml)$", "");
		return new InboxItemDto(
				filename, null, size, io.github.tourgaze.parser.SourceFormat.from(extensionOf(filename)),
				suggestedName, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, List.of(), false, true, origins.get(filename), false, false, false);
	}

	/**
	 * Public so the Garmin scanner can ask "is this hash known?" without a list
	 * call.
	 */
	public boolean isAlreadyImported(String sha256) {
		return activityRepo.findBySourceHash(sha256).isPresent();
	}

	/** True if a file with this sha is already sitting in inbox/ (any filename). */
	public boolean isAlreadyStaged(String sha256) throws IOException {
		try (var stream = Files.list(storage.inboxDir())) {
			return stream
					.filter(Files::isRegularFile)
					.filter(this::isSupported)
					.anyMatch(p -> {
						try {
							return sha256Hex(Files.readAllBytes(p)).equals(sha256);
						} catch (IOException e) {
							return false;
						}
					});
		}
	}

	/**
	 * SHA-256 hashes of every file the user deleted from the inbox (parked in
	 * inbox-ignored/). The watch-folder scanner consults this so a file the user
	 * removed isn't re-copied from the still-present source device on the next
	 * scan — "stays on the Garmin, stays out of the inbox".
	 */
	public Set<String> parkedHashes() {
		Set<String> out = new HashSet<>();
		Path dir = storage.inboxIgnoredDir();
		if (!Files.isDirectory(dir))
			return out;
		try (var s = Files.list(dir)) {
			s.filter(Files::isRegularFile).forEach(p -> {
				try {
					out.add(sha256Hex(Files.readAllBytes(p)));
				} catch (IOException e) {
					log.debug("Could not hash parked file {}: {}", p, e.getMessage());
				}
			});
		} catch (IOException e) {
			log.debug("Could not list parked dir {}: {}", dir, e.getMessage());
		}
		return out;
	}

	public StorageService getStorage() {
		return storage;
	}

	// ── Media (photos/videos) staged against an inbox file, moved to the ride on
	// import ──

	/**
	 * Save dropped photos/videos for a staged inbox file; returns the stored names.
	 */
	public List<String> stageMedia(String filename, org.springframework.web.multipart.MultipartFile[] files)
			throws IOException {
		if (files == null)
			return List.of();
		Path dir = storage.inboxMediaDir(filename);
		Files.createDirectories(dir);
		List<String> saved = new java.util.ArrayList<>();
		for (var f : files) {
			String orig = f.getOriginalFilename();
			if (orig == null || orig.isBlank())
				continue;
			String ext = extensionOf(orig);
			if (!mediaProcessor.isAccepted(ext))
				continue; // images + videos
			String name = uniqueMediaName(dir, sanitizeMediaName(orig));
			// Downscale oversized images; videos pass through untouched.
			Files.write(dir.resolve(name), mediaProcessor.process(f.getBytes(), ext));
			saved.add(name);
		}
		return saved;
	}

	public List<String> listStagedMedia(String filename) throws IOException {
		Path dir = storage.inboxMediaDir(filename);
		if (!Files.isDirectory(dir))
			return List.of();
		try (var s = Files.list(dir)) {
			return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	public Path stagedMediaFile(String filename, String name) {
		if (name.contains("/") || name.contains("\\") || name.contains(".."))
			return null;
		Path p = storage.inboxMediaDir(filename).resolve(name);
		return Files.isRegularFile(p) ? p : null;
	}

	public void deleteStagedMedia(String filename, String name) throws IOException {
		Path p = stagedMediaFile(filename, name);
		if (p != null)
			Files.deleteIfExists(p);
	}

	/** Move staged photos into the ride's permanent media folder on import. */
	private void moveStagedMedia(String inboxFilename, String storeFilename) {
		Path from = storage.inboxMediaDir(inboxFilename);
		if (!Files.isDirectory(from))
			return;
		try {
			Path to = storage.activityMediaDir(storeFilename);
			Files.createDirectories(to);
			try (var s = Files.list(from)) {
				for (Path p : s.filter(Files::isRegularFile).toList()) {
					// Staged media is plaintext (local workspace); encrypt it as it
					// lands in the store when encryption is on.
					storage.writeEncrypted(to.resolve(p.getFileName().toString()), Files.readAllBytes(p));
					Files.delete(p);
				}
			}
			Files.deleteIfExists(from);
		} catch (IOException e) {
			log.warn("Could not move staged media for {}: {}", inboxFilename, e.getMessage());
		}
	}

	/**
	 * Pull photos embedded in the source file (KMZ {@code images/…} referenced by
	 * PhotoOverlays) into the ride's media folder, downscaled like uploads.
	 * Returns name → [lat,lon] for the geo-located ones so the manifest can place
	 * them on the track. Best-effort — never fails the import.
	 */
	private Map<String, double[]> extractEmbeddedPhotos(byte[] data, String ext, Path mediaDir) {
		Map<String, double[]> known = new HashMap<>();
		List<EmbeddedPhoto> photos = trackParser.extractPhotos(data, ext);
		if (photos.isEmpty())
			return known;
		try {
			Files.createDirectories(mediaDir);
			for (EmbeddedPhoto photo : photos) {
				try {
					String pext = extensionOf(photo.name());
					if (!mediaProcessor.isAccepted(pext))
						continue;
					String name = uniqueMediaName(mediaDir, sanitizeMediaName(photo.name()));
					storage.writeEncrypted(mediaDir.resolve(name), mediaProcessor.process(photo.bytes(), pext));
					if (photo.lat() != null && photo.lon() != null) {
						known.put(name, new double[] { photo.lat(), photo.lon() });
					}
				} catch (Exception e) {
					log.warn("[Import] embedded photo '{}' failed: {}", photo.name(), e.getMessage());
				}
			}
		} catch (IOException e) {
			log.warn("[Import] media dir create failed for {}: {}", mediaDir, e.getMessage());
		}
		return known;
	}

	private String sanitizeMediaName(String orig) {
		String name = orig.replaceAll("[^a-zA-Z0-9._-]", "_");
		// Uploads are "private" — never let one masquerade as a discovered
		// (public-prefixed) photo.
		String lower = name.toLowerCase();
		if (lower.startsWith(io.github.tourgaze.service.MediaManifestService.PUBLIC_PREFIX)
				|| lower.startsWith("wiki_")) {
			name = "u_" + name;
		}
		return name.length() > 120 ? name.substring(name.length() - 120) : name;
	}

	private String uniqueMediaName(Path dir, String name) {
		if (!storage.encryptedExists(dir.resolve(name)))
			return name;
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 2;; i++) {
			String c = base + "_" + i + ext;
			if (!storage.encryptedExists(dir.resolve(c)))
				return c;
		}
	}

	/** Lightweight lat/lon point for the inbox route preview (pre-import). */
	public record PreviewPoint(double lat, double lon) {
	}

	/**
	 * Parse an inbox file's track for the AddTour route preview (the file isn't
	 * imported yet, so there's no Activity/cache). Downsampled to ~1000 points so
	 * the payload stays tiny. Returns null if the file is missing, empty list if
	 * it has no GPS / unsupported format.
	 */
	public List<PreviewPoint> trackPreview(String filename) throws IOException {
		if (filename == null || filename.contains("/") || filename.contains("\\") || filename.contains(".."))
			return null;
		Path f = storage.inboxDir().resolve(filename);
		if (!Files.isRegularFile(f))
			return null;
		String ext = extensionOf(filename);
		if (!trackParser.canParse(ext))
			return List.of();
		var pts = trackParser.parse(Files.readAllBytes(f), ext).points();
		if (pts.isEmpty())
			return List.of();
		int step = Math.max(1, pts.size() / 1000);
		List<PreviewPoint> out = new java.util.ArrayList<>();
		for (int i = 0; i < pts.size(); i += step)
			out.add(new PreviewPoint(pts.get(i).lat(), pts.get(i).lon()));
		var last = pts.get(pts.size() - 1);
		PreviewPoint tail = out.get(out.size() - 1);
		if (tail.lat() != last.lat() || tail.lon() != last.lon())
			out.add(new PreviewPoint(last.lat(), last.lon()));
		return out;
	}

	public String hashOf(byte[] bytes) {
		return sha256Hex(bytes);
	}

	/**
	 * Confirm an inbox item → move into store/, create the Activity, apply the
	 * user-supplied form overrides, fire-and-forget weather lookup.
	 *
	 * Transactional so the dedup check + activity save + tag attach all run
	 * under one Hibernate session. Without it, the lazy many-to-many tag
	 * collection mutation outside an open session causes the same
	 * LazyInitializationException that bit us earlier on ActivityController.
	 */
	@Transactional
	public Activity importItem(String filename, InboxImportRequest req) throws IOException {
		Path source = storage.inboxDir().resolve(filename);
		if (!Files.isRegularFile(source))
			throw new IOException("Inbox file not found: " + filename);

		byte[] data = Files.readAllBytes(source);
		String hash = sha256Hex(data);
		String ext = extensionOf(filename);

		// Dedup — if already imported, drop the inbox copy and return the existing row.
		Optional<Activity> existing = activityRepo.findBySourceHash(hash);
		if (existing.isPresent()) {
			Files.deleteIfExists(source);
			// It's already safely in the repo, so a delete-from-device original is
			// now redundant — remove it (no-op for keep-on-device / uploads).
			deleteRecordedSource(filename);
			return existing.get();
		}

		// Per-ride folder: store/<rideId>/<stem>.<ext>. The id is assigned up front
		// so the folder name is known before save (a preset id survives @PrePersist).
		// The original stem is preserved for file-tree recovery; the id folder makes
		// it unique, so no content-hash suffix is needed. moveIntoStore encrypts the
		// file (→ .enc) when store encryption is enabled.
		String stem = filename.replaceAll("(?i)\\.(fit|gpx|tcx|kmz|kml)$", "");
		String safeStem = stem.replaceAll("[^a-zA-Z0-9._\\-]", "_");
		if (safeStem.length() > 80)
			safeStem = safeStem.substring(0, 80);
		if (safeStem.isBlank())
			safeStem = "ride";

		Activity a = new Activity();
		a.setId(io.github.tourgaze.util.ShortId.next());
		String storeFilename = a.getId() + "/" + safeStem + "." + ext;
		storage.moveIntoStore(source, storeFilename);
		a.setSourceHash(hash);
		a.setSourceFilename(storeFilename);
		// The on-disk name (sourceFilename) is the content hash so we can dedup
		// and avoid filesystem-illegal characters. Preserve what the user
		// actually saw — handy in the UI and for any future export round-trip.
		a.setOriginalFilename(filename);
		a.setSourceFormat(ext);
		a.setImportedAt(Instant.now());

		List<io.github.tourgaze.parser.TrackPoint> trackPoints = List.of();
		if (trackParser.canParse(ext)) {
			ParseResult r = trackParser.parse(data, ext);
			trackPoints = r.points();
			// Route fingerprint for "same route" / ghost-chase compare detection.
			if (!trackPoints.isEmpty())
				a.setRouteGeocells(RouteSimilarityService.fingerprint(trackPoints));
			// Sport: an explicit override from the import form wins; otherwise trust
			// the device sport, else infer from pace/distance.
			Double avgKmh = r.avgSpeedMs() != null ? r.avgSpeedMs() * 3.6
					: (r.durationS() != null && r.durationS() > 0 && r.distanceM() != null
							? (r.distanceM() / 1000.0) / (r.durationS() / 3600.0)
							: null);
			Double distKm = r.distanceM() != null ? r.distanceM() / 1000.0 : null;
			var detectedType = proposalService.proposeType(r.sport(), avgKmh, distKm);
			// An explicit override from the form (a Sport key) is stored as-is; else
			// the detected enum's wire value.
			a.setActivityType((req.activityType() != null && !req.activityType().isBlank())
					? req.activityType().trim().toLowerCase()
					: detectedType.wire());
			a.setSubSport(r.subSport()); // captured for future discipline detail
			a.setStartTime(r.startTime());
			a.setEndTime(r.endTime());
			a.setDurationS(r.durationS());
			a.setMovingTimeS(r.movingTimeS());
			// Start-time override from the form (Garmin sometimes records a bad
			// start): shift end by the same duration so the elapsed time is kept.
			if (req.startTime() != null) {
				a.setStartTime(req.startTime());
				a.setEndTime(a.getDurationS() != null
						? req.startTime().plusSeconds(a.getDurationS())
						: r.endTime());
			}
			a.setDistanceKm(r.distanceM() != null ? r.distanceM() / 1000.0 : null);
			a.setElevationGainM(r.ascentM());
			a.setElevationLossM(r.descentM());
			a.setAvgHr(r.avgHr());
			a.setMaxHr(r.maxHr());
			a.setAvgSpeedKmh(r.avgSpeedMs() != null ? r.avgSpeedMs() * 3.6 : null);
			a.setMaxSpeedKmh(r.maxSpeedMs() != null ? r.maxSpeedMs() * 3.6 : null);
			a.setAvgCadence(r.avgCadence());
			a.setMaxCadence(r.maxCadence());
			a.setAvgPowerW(r.avgPowerW());
			a.setMaxPowerW(r.maxPowerW());
			if (!r.points().isEmpty()) {
				a.setStartLat(r.points().get(0).lat());
				a.setStartLon(r.points().get(0).lon());
			}
			// Warm tile cache for the route — non-blocking.
			tileWarmer.warmAsync(r.points());
			// Which sensor channels this ride actually carries → attributes json (no
			// schema). Powers the ride-detail page and "rides with power" filtering.
			java.util.List<String> sensors = new java.util.ArrayList<>();
			if (r.avgHr() != null)
				sensors.add("hr");
			if (r.avgCadence() != null)
				sensors.add("cadence");
			if (r.avgPowerW() != null)
				sensors.add("power");
			if (!r.points().isEmpty() && (r.points().get(0).lat() != 0 || r.points().get(0).lon() != 0))
				sensors.add("gps");
			if (!sensors.isEmpty())
				a.getAttributes().put("sensors", sensors);
		}

		// Apply form overrides.
		if (req != null) {
			if (req.name() != null && !req.name().isBlank())
				a.setName(req.name().trim());
			if (req.description() != null)
				a.setDescription(req.description());
			if (req.gearId() != null && !req.gearId().isBlank()) {
				gearRepo.findById(req.gearId()).ifPresent(a::setGear);
			}
			if (req.userId() != null && !req.userId().isBlank()) {
				userRepo.findById(req.userId()).ifPresent(a::setUser);
			}
			if (req.tagIds() != null && !req.tagIds().isEmpty()) {
				List<Tag> tags = tagRepo.findAllById(req.tagIds());
				a.setTags(new HashSet<>(tags));
			}
			// Accepted region/country (etc.) proposals arrive as names → find-or-create
			// the root tag and apply it, on top of any id-based tags above.
			if (req.tagNames() != null && !req.tagNames().isEmpty()) {
				for (String tn : req.tagNames()) {
					Tag t = findOrCreateRootTag(tn);
					if (t != null)
						a.getTags().add(t);
				}
			}
			if (req.weatherTempC() != null)
				a.getWeather().setTempC(req.weatherTempC());
			if (req.weatherHumidityPct() != null)
				a.getWeather().setHumidityPct(req.weatherHumidityPct());
			if (req.weatherWindKph() != null)
				a.getWeather().setWindKph(req.weatherWindKph());
			if (req.weatherCondition() != null)
				a.getWeather().setCondition(req.weatherCondition());
			if (req.weatherTempC() != null || req.weatherCondition() != null) {
				a.getWeather().setFetchedAt(Instant.now());
			}
			if (req.weightKg() != null)
				a.setWeightKg(req.weightKg());
			if (req.startLocation() != null)
				a.setStartLocation(req.startLocation());
			if (req.startCountry() != null)
				a.setStartCountry(req.startCountry().toUpperCase());
			if (req.endLocation() != null)
				a.setEndLocation(req.endLocation());
			if (req.endCountry() != null)
				a.setEndCountry(req.endCountry().toUpperCase());
		}
		// Server-side fallback: if the frontend didn't pre-populate location
		// strings via the predict endpoint (e.g. when importing programmatically
		// or when the predict request failed), derive them from GPS at import
		// time. PredictionService caches Nominatim hits for a week, so this is
		// a free read if the user already viewed the AddTour panel for this
		// ride. End location goes through the same call — endLat/endLon are
		// null here so it only resolves the start, which is the common case
		// for loop rides anyway.
		if (a.getStartLocation() == null && a.getStartLat() != null && a.getStartLon() != null) {
			try {
				var pred = predictionService.predict(
						a.getStartLat(), a.getStartLon(),
						null, null,
						a.getDistanceKm());
				a.setStartLocation(pred.startLocation());
				if (a.getStartCountry() == null && pred.country() != null)
					a.setStartCountry(pred.country());
				if (a.getEndLocation() == null)
					a.setEndLocation(pred.endLocation());
				if (a.getEndCountry() == null && pred.country() != null)
					a.setEndCountry(pred.country());
			} catch (Exception ex) {
				log.warn("Location lookup failed for {}: {}", a.getId(), ex.getMessage());
			}
		}
		if (a.getName() == null || a.getName().isBlank()) {
			a.setName(filename.replaceAll("(?i)\\.(fit|gpx|tcx|kmz|kml)$", ""));
		}
		// Assign to default user if none was passed and exactly one exists.
		if (a.getUser() == null) {
			List<User> all = userRepo.findAll();
			if (all.size() == 1)
				a.setUser(all.get(0));
		}

		// Default weight from the rider's current profile weight if the form
		// didn't override. Captures the point-in-time value at import — future
		// edits to the user's profile weight don't retroactively change it.
		if (a.getWeightKg() == null && a.getUser() != null) {
			a.setWeightKg(a.getUser().getWeightKg());
		}

		// Estimated energy expenditure (kcal) from the best available signal —
		// power meter on a bike, else heart rate + body profile (see CalorieEstimator).
		a.setCalories(CalorieEstimator.estimate(a.getActivityType(), a.getAvgPowerW(), a.getAvgHr(),
				a.getMovingTimeS(), a.getDurationS(), a.getWeightKg(), ageOf(a.getUser()),
				a.getUser() != null ? a.getUser().getGender() : null));

		a = activityRepo.save(a);

		// Warm any uncovered map regions this ride touches → cached OSM peaks/
		// passes for auto-detected highlights. Async + best-effort (one Overpass
		// call per new geohash cell), so the import POST returns immediately.
		peakPass.ensureRegionsForActivityAsync(a.getId());

		// Virtual ride pre-cache: full-res track JSON + chart JSON both built
		// async so the first browse to /tour/{id} streams from disk instead of
		// re-parsing the FIT (the map endpoint hits the full-res cache, the
		// elevation chart hits the chart cache). Tiles already warming above,
		// weather fetched below — all fire-and-forget so the import POST
		// returns immediately.
		// Move any photos dropped during staging into the ride's media folder,
		// plus any photos embedded in the source file itself (KMZ PhotoOverlays),
		// then geo-match them all to the track → media.json. Embedded photos carry
		// their KML coordinates (knownCoords), which win over EXIF.
		moveStagedMedia(filename, storeFilename);
		Path mediaDir = storage.activityMediaDir(storeFilename);
		Map<String, double[]> knownCoords = extractEmbeddedPhotos(data, ext, mediaDir);
		mediaManifest.build(mediaDir, trackPoints, knownCoords);
		// Personal photos dropped on AddTour are authored by the ride's rider.
		if (a.getUser() != null) {
			String rider = a.getUser().getDisplayName() != null && !a.getUser().getDisplayName().isBlank()
					? a.getUser().getDisplayName()
					: a.getUser().getUsername();
			for (var it : mediaManifest.read(mediaDir)) {
				if (!"public".equals(it.origin()) && it.author() == null) {
					mediaManifest.setAuthor(mediaDir, it.name(), rider);
				}
			}
		}

		trackCache.prewarmAllAsync(a.getId(), a.getSourceFilename());
		// Weather + a rain shower or two along the route (pinned as ride events).
		weatherService.fetchAndStoreAsync(a.getId(), trackPoints);
		// Write the recovery sidecar after this import commits (async, off-thread).
		events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(a.getId()));

		// Now that the file is safely in store/, delete the device original for a
		// delete-from-device source (no-op for keep-on-device / uploads). Only ever
		// here — never at scan time — so a file is never single-homed off-repo.
		deleteRecordedSource(filename);

		log.info("Imported '{}' as activity {}", filename, a.getId());
		return a;
	}

	/** Whole years from the rider's date of birth to today, or null. */
	private static Integer ageOf(User u) {
		if (u == null || u.getDateOfBirth() == null)
			return null;
		return (int) java.time.temporal.ChronoUnit.YEARS.between(u.getDateOfBirth(), java.time.LocalDate.now());
	}

	/**
	 * Find an already-imported ride whose route overlaps the given staged track by
	 * at least {@link #SAME_TRACK_OVERLAP} (Jaccard on the geohash-cell
	 * fingerprint) — i.e. this file is effectively the same track recorded again
	 * (not byte-identical, which the SHA dedup already sweeps). {@code candidates}
	 * is the cached scalar projection of existing rides, passed in so a whole
	 * inbox listing runs one query, not one per file. Returns the strongest match,
	 * or null when none reaches the threshold.
	 */
	private RouteCandidate findSameTrack(List<io.github.tourgaze.parser.TrackPoint> points,
			List<RouteCandidate> candidates) {
		if (points.isEmpty())
			return null;
		Set<String> cells = toCells(RouteSimilarityService.fingerprint(points));
		if (cells.size() < 8)
			return null; // too short to confidently call it the "same track"
		RouteCandidate best = null;
		double bestJac = 0;
		for (RouteCandidate c : candidates) {
			Set<String> other = toCells(c.routeGeocells());
			if (other.isEmpty())
				continue;
			Set<String> inter = new HashSet<>(cells);
			inter.retainAll(other);
			int union = cells.size() + other.size() - inter.size();
			double jaccard = union == 0 ? 0 : (double) inter.size() / union;
			if (jaccard >= SAME_TRACK_OVERLAP && jaccard > bestJac) {
				bestJac = jaccard;
				best = c;
			}
		}
		return best;
	}

	private static Set<String> toCells(String fingerprint) {
		if (fingerprint == null || fingerprint.isBlank())
			return Set.of();
		return new HashSet<>(List.of(fingerprint.trim().split("\\s+")));
	}

	/** Best-effort reverse-geocode + tag-vote proposal; null on any failure. */
	private PredictionDto safePredict(Double lat, Double lon, Double endLat, Double endLon, Double distanceKm) {
		try {
			return predictionService.predict(lat, lon, endLat, endLon, distanceKm);
		} catch (Exception e) {
			log.debug("[Inbox] prediction failed: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * Display names for the card's suggested-tag chips: existing nearby-ride tags
	 * only. Region/country are NOT proposed as tags — they're saved as first-class
	 * activity fields
	 * ({@code startLocation}/{@code startCountry}/{@code endLocation}
	 * /{@code endCountry}), so turning them into tags too would just be redundant
	 * noise mixed into the user's hand-made tag space.
	 */
	private List<String> proposedTagNames(PredictionDto pred) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		if (pred.suggestedTagIds() != null && !pred.suggestedTagIds().isEmpty())
			tagRepo.findAllById(pred.suggestedTagIds()).forEach(t -> out.add(t.getName()));
		return new java.util.ArrayList<>(out);
	}

	/**
	 * Find an existing root-level tag by name (case-insensitive) or create one —
	 * used to materialise accepted region/country proposals at import. Colour is
	 * deterministic from the name so the same place always gets the same swatch.
	 */
	private Tag findOrCreateRootTag(String name) {
		String n = name == null ? "" : name.trim();
		if (n.isEmpty())
			return null;
		List<Tag> existing = tagRepo.findByParentIsNullAndNameIgnoreCase(n);
		if (!existing.isEmpty())
			return existing.get(0);
		Tag t = new Tag();
		t.setName(n);
		t.setColor(String.format("#%06x", n.toLowerCase(Locale.ROOT).hashCode() & 0xFFFFFF));
		return tagRepo.save(t);
	}

	/**
	 * "Delete from inbox" — the single user-initiated remove. The file leaves
	 * inbox/ for inbox-ignored/ (bytes kept, drag back to re-stage) and its hash is
	 * parked so a keep-on-device source won't re-stage it. We never destroy the
	 * bytes; the only real deletion path is the activity-delete endpoint after a
	 * successful import.
	 */
	public void discard(String filename) throws IOException {
		moveOutOfInbox(filename, storage.inboxIgnoredDir(), "Removed");
	}

	/**
	 * Move a file out of inbox/ into {@code destDir}, never destroying bytes.
	 * Collisions get a -1, -2, … suffix so a previously-parked same-named file is
	 * never overwritten.
	 */
	private void moveOutOfInbox(String filename, Path destDir, String verb) throws IOException {
		Path file = storage.inboxDir().resolve(filename);
		if (!Files.isRegularFile(file))
			return;
		Files.createDirectories(destDir);
		Path dest = destDir.resolve(filename);
		int n = 1;
		while (Files.exists(dest) && n < 100) {
			int dot = filename.lastIndexOf('.');
			String stem = dot > 0 ? filename.substring(0, dot) : filename;
			String ext = dot > 0 ? filename.substring(dot) : "";
			dest = destDir.resolve(stem + "-" + n + ext);
			n++;
		}
		Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
		log.info("{} inbox file '{}' → {}", verb, filename, dest.getFileName());
	}

	private boolean isSupported(Path p) {
		return io.github.tourgaze.parser.SourceFormat.from(extensionOf(p.getFileName().toString())) != null;
	}

	private String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}

	private String sha256Hex(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			StringBuilder hex = new StringBuilder(2 * hash.length);
			for (byte b : hash) {
				String h = Integer.toHexString(0xff & b);
				if (h.length() == 1)
					hex.append('0');
				hex.append(h);
			}
			return hex.toString();
		} catch (Exception e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}
}
