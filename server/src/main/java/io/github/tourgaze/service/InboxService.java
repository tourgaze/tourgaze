/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.tourgaze.dto.InboxImportRequest;
import io.github.tourgaze.dto.InboxItemDto;
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
			PeakPassService peakPass) {
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
	}

	public List<InboxItemDto> listPending() throws IOException {
		try (var stream = Files.list(storage.inboxDir())) {
			return stream
					.filter(Files::isRegularFile)
					.filter(this::isSupported)
					.map(this::stageOrSweepDuplicate)
					.filter(java.util.Objects::nonNull)
					.toList();
		}
	}

	/**
	 * Stages a file as a pending inbox item — except when its hash already
	 * matches an imported activity, in which case it silently cleans up the
	 * duplicate so it never resurfaces. Caller filters out {@code null}.
	 */
	private InboxItemDto stageOrSweepDuplicate(Path file) {
		InboxItemDto item = stage(file);
		if (item == null)
			return null;
		if (item.existingActivityId() != null) {
			// Sweep the duplicate: the same hash is already imported, so the
			// bytes in inbox/ are redundant. If the delete fails (locked,
			// permissions), the next list call sees the same hit and tries
			// again — non-fatal.
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				log.debug("Could not sweep duplicate {}: {}", file, e.getMessage());
			}
			return null;
		}
		return item;
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
					Files.move(p, to.resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
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
					Files.write(mediaDir.resolve(name), mediaProcessor.process(photo.bytes(), pext));
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
		if (!Files.exists(dir.resolve(name)))
			return name;
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int i = 2;; i++) {
			String c = base + "_" + i + ext;
			if (!Files.exists(dir.resolve(c)))
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

	private InboxItemDto stage(Path file) {
		try {
			String filename = file.getFileName().toString();
			String ext = extensionOf(filename);
			long size = Files.size(file);
			byte[] data = Files.readAllBytes(file);
			String sha = sha256Hex(data);

			// If the same content is already imported, surface that to the UI so it
			// can offer a "discard" button instead of re-importing.
			String existingId = activityRepo.findBySourceHash(sha).map(Activity::getId).orElse(null);

			String suggestedName = filename.replaceAll("(?i)\\.(fit|gpx|tcx|kmz|kml)$", "");

			if (trackParser.canParse(ext)) {
				try {
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
					// Reasonable defaults: obvious activity type + a gear guess from history.
					var type = proposalService.proposeType(r.sport(), avgKmh);
					var gear = proposalService.proposeGear(avgKmh, distanceKm, r.ascentM(), type);
					return new InboxItemDto(
							filename, sha, size,
							io.github.tourgaze.parser.SourceFormat.from(ext), suggestedName,
							type,
							r.startTime(), distanceKm,
							r.durationS(), lat, lon, endLat, endLon, existingId,
							gear != null ? gear.gearId() : null, gear != null ? gear.gearName() : null);
				} catch (Exception e) {
					log.warn("Could not parse {}: {}", filename, e.getMessage());
				}
			}

			// TCX / unknown formats not yet parsed — surface a bare-bones stub so the
			// user still sees the file in the inbox UI and can discard it.
			return new InboxItemDto(
					filename, sha, size,
					io.github.tourgaze.parser.SourceFormat.from(ext), suggestedName,
					null, null, null, null, null, null, null, null, existingId, null, null);
		} catch (IOException e) {
			log.warn("Cannot read inbox file {}: {}", file, e.getMessage());
			return null;
		}
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
		// Storage filename: `{sanitized-original-stem}_{hash}.{ext}`. The hash
		// suffix keeps the file content-addressable for dedup, but the
		// original filename stem is preserved at the front so a DB-loss
		// disaster (corrupt H2, accidental schema rewipe, ransomware) still
		// leaves a recoverable file tree — you can see "2024-06-13-tegernsee_…"
		// sitting in `store/` and re-import. Truncate the stem to 100 chars
		// so even with very long names + 64-char hash + ext we stay under
		// typical 255-char filesystem limits.
		String stem = filename.replaceAll("(?i)\\.(fit|gpx|tcx|kmz|kml)$", "");
		String safeStem = stem.replaceAll("[^a-zA-Z0-9._\\-]", "_");
		if (safeStem.length() > 100)
			safeStem = safeStem.substring(0, 100);
		if (safeStem.isBlank())
			safeStem = "ride";
		String storeFilename = safeStem + "_" + hash + "." + ext;

		// Dedup — if already imported, drop the inbox copy and return the existing row.
		Optional<Activity> existing = activityRepo.findBySourceHash(hash);
		if (existing.isPresent()) {
			Files.deleteIfExists(source);
			return existing.get();
		}

		Files.move(source, storage.storeFile(storeFilename), StandardCopyOption.REPLACE_EXISTING);

		Activity a = new Activity();
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
			// Apply the proposed type (the form shows it but can't edit it): trust the
			// device sport, else infer from pace, else cycling.
			Double avgKmh = r.avgSpeedMs() != null ? r.avgSpeedMs() * 3.6
					: (r.durationS() != null && r.durationS() > 0 && r.distanceM() != null
							? (r.distanceM() / 1000.0) / (r.durationS() / 3600.0)
							: null);
			a.setActivityType(proposalService.proposeType(r.sport(), avgKmh).wire());
			a.setStartTime(r.startTime());
			a.setEndTime(r.endTime());
			a.setDurationS(r.durationS());
			a.setMovingTimeS(r.movingTimeS());
			a.setDistanceKm(r.distanceM() != null ? r.distanceM() / 1000.0 : null);
			a.setElevationGainM(r.ascentM());
			a.setAvgHr(r.avgHr());
			a.setMaxHr(r.maxHr());
			a.setAvgSpeedKmh(r.avgSpeedMs() != null ? r.avgSpeedMs() * 3.6 : null);
			a.setMaxSpeedKmh(r.maxSpeedMs() != null ? r.maxSpeedMs() * 3.6 : null);
			if (!r.points().isEmpty()) {
				a.setStartLat(r.points().get(0).lat());
				a.setStartLon(r.points().get(0).lon());
			}
			// Warm tile cache for the route — non-blocking.
			tileWarmer.warmAsync(r.points());
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
		weatherService.fetchAndStoreAsync(a.getId());
		// Write the recovery sidecar after this import commits (async, off-thread).
		events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(a.getId()));

		log.info("Imported '{}' as activity {}", filename, a.getId());
		return a;
	}

	/**
	 * "Ignore" — move the file out of inbox/ into inbox-ignored/ instead of
	 * deleting it. The user can drag it back into inbox/ to re-stage. We
	 * never destroy the bytes; the only real deletion path is via the
	 * activity-delete endpoint after a successful import.
	 */
	public void discard(String filename) throws IOException {
		Path file = storage.inboxDir().resolve(filename);
		if (!Files.isRegularFile(file))
			return;
		Path destDir = storage.inboxIgnoredDir();
		Files.createDirectories(destDir);
		Path dest = destDir.resolve(filename);
		// Collision: a same-named file was already ignored once. Suffix with
		// -1, -2, … so we never silently overwrite previously-ignored bytes.
		int n = 1;
		while (Files.exists(dest) && n < 100) {
			int dot = filename.lastIndexOf('.');
			String stem = dot > 0 ? filename.substring(0, dot) : filename;
			String ext = dot > 0 ? filename.substring(dot) : "";
			dest = destDir.resolve(stem + "-" + n + ext);
			n++;
		}
		Files.move(file, dest, StandardCopyOption.REPLACE_EXISTING);
		log.info("Ignored inbox file '{}' → {}", filename, dest.getFileName());
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
