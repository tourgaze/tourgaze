/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.tourgaze.dto.GearDto;
import io.github.tourgaze.dto.LibraryMetadataDto;
import io.github.tourgaze.dto.RideMetadataDto;
import io.github.tourgaze.dto.UserDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.entity.User;
import io.github.tourgaze.parser.ParseResult;
import io.github.tourgaze.parser.TrackParser;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.repository.TagRepository;
import io.github.tourgaze.repository.UserRepository;
import io.github.tourgaze.store.StorageService;

/**
 * Disaster recovery: rebuild the DB rows from the per-ride
 * {@code store/<id>/<name>.metadata.json} sidecars written by
 * {@link RideExportService}. The store (ride files + sidecars + media) is the
 * cloud-syncable source of truth; the H2 DB is a rebuildable cache. This reads
 * each sidecar, re-parses its source track for the fields that aren't in the
 * sidecar (cadence / power / sub-sport / route fingerprint), and overlays the
 * sidecar's authoritative metadata — preserving the original activity id so
 * store paths, media and the sidecars themselves keep lining up.
 *
 * Idempotent: a ride whose id already exists in the DB is skipped, so this is
 * safe to run on a partially-populated DB or repeatedly.
 */
@Service
public class RideRecoveryService {

	private static final Logger log = LoggerFactory.getLogger(RideRecoveryService.class);

	private final StorageService storage;
	private final ObjectMapper objectMapper;
	private final TrackParser trackParser;
	private final ActivityRepository activityRepo;
	private final GearRepository gearRepo;
	private final TagRepository tagRepo;
	private final UserRepository userRepo;
	private final TrackCacheService trackCache;

	// Self-reference so each per-ride recovery runs in its own transaction (one
	// bad ride doesn't roll back the whole sweep).
	@Autowired
	@Lazy
	private RideRecoveryService self;

	public RideRecoveryService(StorageService storage, ObjectMapper objectMapper, TrackParser trackParser,
			ActivityRepository activityRepo, GearRepository gearRepo, TagRepository tagRepo,
			UserRepository userRepo, TrackCacheService trackCache) {
		this.storage = storage;
		this.objectMapper = objectMapper;
		this.trackParser = trackParser;
		this.activityRepo = activityRepo;
		this.gearRepo = gearRepo;
		this.tagRepo = tagRepo;
		this.userRepo = userRepo;
		this.trackCache = trackCache;
	}

	/** Outcome of a recovery sweep. */
	public record RecoveryReport(int scanned, int recovered, int skipped, int failed,
			int usersRestored, int gearRestored, List<String> errors) {
	}

	/**
	 * Scan every sidecar under store/ and recover any ride not already in the DB.
	 *
	 * The store can hold stale, duplicate sidecars from earlier import
	 * generations (a re-import mints a new id, leaving the old store/<id>/ folder
	 * behind). We therefore (a) process the freshest sidecar first
	 * (exportedAt desc) and (b) dedup by source content hash, so byte-identical
	 * duplicates collapse to one ride with its most recent metadata.
	 */
	public RecoveryReport recoverAll() {
		List<Path> sidecars = findSidecars();
		int recovered = 0, skipped = 0, failed = 0;
		List<String> errors = new ArrayList<>();

		// Restore rider profiles + the full gear list FIRST, so per-ride rider /
		// gear resolution below can link to them. Without this, a rebuilt DB loses
		// the user and any gear not attached to a ride.
		int usersRestored = 0, gearRestored = 0;
		try {
			int[] lib = self.restoreLibrary();
			usersRestored = lib[0];
			gearRestored = lib[1];
		} catch (Exception e) {
			errors.add("library.metadata.json: " + e.getMessage());
			log.warn("[Recovery] Library restore failed: {}", e.getMessage());
		}

		// Parse up front so we can order by recency before recovering.
		List<RideMetadataDto> dtos = new ArrayList<>();
		for (Path p : sidecars) {
			try {
				dtos.add(objectMapper.readValue(p.toFile(), RideMetadataDto.class));
			} catch (Exception e) {
				failed++;
				errors.add(p.getFileName() + ": " + e.getMessage());
				log.warn("[Recovery] Unreadable sidecar {}: {}", p.getFileName(), e.getMessage());
			}
		}
		dtos.sort(Comparator.comparing(RideMetadataDto::exportedAt,
				Comparator.nullsLast(Comparator.reverseOrder())));

		for (RideMetadataDto dto : dtos) {
			try {
				String status = self.recoverOne(dto);
				if ("recovered".equals(status))
					recovered++;
				else
					skipped++;
			} catch (Exception e) {
				failed++;
				String msg = (dto.id() == null ? "?" : dto.id()) + ": " + e.getMessage();
				errors.add(msg);
				log.warn("[Recovery] Failed for {}", msg);
			}
		}
		log.info("[Recovery] scanned={} recovered={} skipped={} failed={} usersRestored={} gearRestored={}",
				sidecars.size(), recovered, skipped, failed, usersRestored, gearRestored);
		return new RecoveryReport(sidecars.size(), recovered, skipped, failed,
				usersRestored, gearRestored, errors);
	}

	/**
	 * Restore the rider profile(s) + full gear list from the library sidecar
	 * ({@code store/library.metadata.json}), preserving ids. Idempotent. Returns
	 * {@code [usersRestored, gearRestored]}.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int[] restoreLibrary() throws Exception {
		Path lib = storage.storeDir().resolve("library.metadata.json");
		if (!Files.isRegularFile(lib))
			return new int[] { 0, 0 };
		LibraryMetadataDto dto = objectMapper.readValue(lib.toFile(), LibraryMetadataDto.class);
		int u = 0, g = 0;
		for (UserDto ud : dto.users() == null ? List.<UserDto>of() : dto.users()) {
			if (ud.id() == null || userRepo.existsById(ud.id()))
				continue;
			User user = new User();
			user.setId(ud.id());
			user.setUsername(ud.username());
			user.setDisplayName(ud.displayName());
			user.setCreatedAt(ud.createdAt());
			user.setDateOfBirth(ud.dateOfBirth());
			user.setHeightCm(ud.heightCm());
			user.setWeightKg(ud.weightKg());
			user.setGender(ud.gender());
			user.setRestingHr(ud.restingHr());
			user.setMaxHr(ud.maxHr());
			userRepo.save(user);
			u++;
		}
		for (GearDto gd : dto.gear() == null ? List.<GearDto>of() : dto.gear()) {
			if (gd.id() == null || gearRepo.existsById(gd.id()))
				continue;
			Gear gear = new Gear();
			gear.setId(gd.id());
			gear.setName(gd.name());
			gear.setType(gd.type());
			gear.setDescription(gd.description());
			gear.setIcon(gd.icon());
			gear.setAssisted(gd.assisted());
			gear.setWeightKg(gd.weightKg());
			if (gd.userId() != null)
				userRepo.findById(gd.userId()).ifPresent(gear::setUser);
			gearRepo.save(gear);
			g++;
		}
		log.info("[Recovery] library restored: {} user(s), {} gear", u, g);
		return new int[] { u, g };
	}

	/** All {@code *.metadata.json} sidecars, one per ride folder under store/. */
	private List<Path> findSidecars() {
		Path store = storage.storeDir();
		if (!Files.isDirectory(store))
			return List.of();
		try (var walk = Files.walk(store, 2)) {
			return walk.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".metadata.json"))
					// library.metadata.json is the library sidecar (users + gear),
					// restored separately — not a per-ride sidecar.
					.filter(p -> !p.getFileName().toString().equals("library.metadata.json"))
					.sorted()
					.toList();
		} catch (Exception e) {
			log.warn("[Recovery] Could not list sidecars: {}", e.getMessage());
			return List.of();
		}
	}

	/**
	 * Recover a single ride from its sidecar. Own transaction so failures are
	 * isolated. Returns "recovered" or "skipped".
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public String recoverOne(RideMetadataDto dto) throws Exception {
		if (dto.id() == null || dto.id().isBlank())
			throw new IllegalStateException("sidecar has no activity id");
		if (activityRepo.existsById(dto.id()))
			return "skipped";

		RideMetadataDto.SourceRef src = dto.source();
		if (src == null || src.sourceFilename() == null)
			throw new IllegalStateException("sidecar has no source file reference");
		// Dedup by content hash: a stale duplicate sidecar (older import
		// generation, different id) whose bytes already came back as another ride.
		if (src.sourceHash() != null && activityRepo.findBySourceHash(src.sourceHash()).isPresent())
			return "skipped";
		String relName = src.sourceFilename();
		String ext = extensionOf(relName);

		Activity a = new Activity();
		a.setId(dto.id()); // preserve the original id so store/media/sidecars stay aligned
		a.setSourceFilename(relName);
		a.setOriginalFilename(src.originalFilename());
		a.setSourceFormat(ext);
		a.setSourceHash(src.sourceHash());
		a.setImportedAt(dto.importedAt());

		// Re-parse the original track for the fields the sidecar intentionally
		// omits (recomputable): cadence, power, sub-sport, route fingerprint, and
		// the start point. Everything else comes from the sidecar below.
		if (trackParser.canParse(ext)) {
			byte[] data = storage.readStoreBytes(relName);
			ParseResult r = trackParser.parse(data, ext);
			if (!r.points().isEmpty()) {
				a.setRouteGeocells(RouteSimilarityService.fingerprint(r.points()));
				a.setStartLat(r.points().get(0).lat());
				a.setStartLon(r.points().get(0).lon());
			}
			a.setSubSport(r.subSport());
			a.setAvgCadence(r.avgCadence());
			a.setMaxCadence(r.maxCadence());
			a.setAvgPowerW(r.avgPowerW());
			a.setMaxPowerW(r.maxPowerW());
		}

		// Authoritative DB state from the sidecar (overrides parse where they overlap).
		a.setName(dto.name());
		a.setDescription(dto.description());
		a.setActivityType(dto.activityType());
		a.setStartTime(dto.startTime());
		a.setEndTime(dto.endTime());
		a.setDurationS(dto.durationS());
		a.setMovingTimeS(dto.movingTimeS());
		a.setDistanceKm(dto.distanceKm());
		a.setElevationGainM(dto.elevationGainM());
		a.setAvgHr(dto.avgHr());
		a.setMaxHr(dto.maxHr());
		a.setAvgSpeedKmh(dto.avgSpeedKmh());
		a.setMaxSpeedKmh(dto.maxSpeedKmh());
		if (dto.startLat() != null)
			a.setStartLat(dto.startLat());
		if (dto.startLon() != null)
			a.setStartLon(dto.startLon());
		a.setStartLocation(dto.startLocation());
		a.setStartCountry(dto.startCountry());
		a.setEndLocation(dto.endLocation());
		a.setEndCountry(dto.endCountry());
		a.setWeightKg(dto.weightKg());
		a.setAttributes(dto.attributes());

		a.getWeather().setTempC(dto.weatherTempC());
		a.getWeather().setHumidityPct(dto.weatherHumidityPct());
		a.getWeather().setWindKph(dto.weatherWindKph());
		a.getWeather().setCondition(dto.weatherCondition());
		a.getWeather().setFetchedAt(dto.weatherFetchedAt());

		a.setUser(resolveRider(dto.rider()));
		a.setGear(resolveGear(dto.gear(), a.getUser()));
		for (RideMetadataDto.TagRef tr : dto.tags() == null ? List.<RideMetadataDto.TagRef>of() : dto.tags()) {
			Tag t = findOrCreateRootTag(tr.name());
			if (t != null)
				a.getTags().add(t);
		}

		activityRepo.save(a);
		// Rebuild the derived track / chart caches from the source file (async).
		trackCache.prewarmAllAsync(a.getId(), a.getSourceFilename());
		log.info("[Recovery] recovered activity {} ({})", a.getId(), a.getName());
		return "recovered";
	}

	// ── Resolve-by-name helpers (recovery survives id changes) ────────────────

	private User resolveRider(RideMetadataDto.RiderRef ref) {
		List<User> all = userRepo.findAll();
		if (ref != null) {
			for (User u : all) {
				if (ref.username() != null && ref.username().equalsIgnoreCase(u.getUsername()))
					return u;
				if (ref.displayName() != null && ref.displayName().equalsIgnoreCase(u.getDisplayName()))
					return u;
			}
		}
		return all.size() == 1 ? all.get(0) : null;
	}

	private Gear resolveGear(RideMetadataDto.GearRef ref, User owner) {
		if (ref == null || ref.name() == null || ref.name().isBlank())
			return null;
		String name = ref.name().trim();
		for (Gear g : gearRepo.findAll())
			if (name.equalsIgnoreCase(g.getName()))
				return g;
		Gear g = new Gear();
		g.setName(name);
		g.setType(ref.type());
		g.setWeightKg(ref.weightKg());
		if (owner != null)
			g.setUser(owner);
		return gearRepo.save(g);
	}

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

	private static String extensionOf(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
	}
}
