/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import io.github.tourgaze.dto.ActivitySummaryDto;
import io.github.tourgaze.dto.ActivityUpdateDto;
import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.entity.Tag;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.repository.TagRepository;
import io.github.tourgaze.service.TrackCacheService;
import io.github.tourgaze.store.StorageService;

/**
 * Class-level @Transactional keeps the Hibernate session open across the
 * controller method + DTO mapping, so the lazy {@code Activity.tags} relation
 * can be walked when building {@link ActivitySummaryDto#tagIds()} without
 * blowing up with {@code LazyInitializationException}.
 */
@RestController
@RequestMapping("/api/activities")
@Transactional(readOnly = true)
public class ActivityController {

	private static final Logger log = LoggerFactory.getLogger(ActivityController.class);

	/** Plain reader for the on-disk track cache (default config is fine). */
	private static final com.fasterxml.jackson.databind.ObjectMapper RAW_MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

	private final ActivityRepository activityRepo;
	private final GearRepository gearRepo;
	private final TagRepository tagRepo;
	private final TrackCacheService trackCache;
	private final StorageService storage;
	private final io.github.tourgaze.service.RideMetadataMapper metadataMapper;
	private final io.github.tourgaze.service.MediaManifestService mediaManifest;
	private final io.github.tourgaze.service.WikimediaPhotoService wikimedia;
	private final io.github.tourgaze.service.RideMediaService rideMedia;
	private final io.github.tourgaze.service.RouteSimilarityService routeSimilarity;
	private final io.github.tourgaze.service.mapper.ActivitySummaryMapper summaryMapper;
	private final org.springframework.context.ApplicationEventPublisher events;

	public ActivityController(ActivityRepository activityRepo,
			GearRepository gearRepo,
			TagRepository tagRepo,
			TrackCacheService trackCache,
			StorageService storage,
			io.github.tourgaze.service.RideMetadataMapper metadataMapper,
			io.github.tourgaze.service.MediaManifestService mediaManifest,
			io.github.tourgaze.service.WikimediaPhotoService wikimedia,
			io.github.tourgaze.service.RideMediaService rideMedia,
			io.github.tourgaze.service.RouteSimilarityService routeSimilarity,
			io.github.tourgaze.service.mapper.ActivitySummaryMapper summaryMapper,
			org.springframework.context.ApplicationEventPublisher events) {
		this.activityRepo = activityRepo;
		this.gearRepo = gearRepo;
		this.tagRepo = tagRepo;
		this.trackCache = trackCache;
		this.storage = storage;
		this.metadataMapper = metadataMapper;
		this.mediaManifest = mediaManifest;
		this.wikimedia = wikimedia;
		this.rideMedia = rideMedia;
		this.routeSimilarity = routeSimilarity;
		this.summaryMapper = summaryMapper;
		this.events = events;
	}

	@GetMapping
	public List<ActivitySummaryDto> list() {
		return activityRepo.findAllByOrderByStartTimeDesc().stream()
				.map(this::toSummary)
				.toList();
	}

	@GetMapping("/{id}")
	public ResponseEntity<ActivitySummaryDto> get(@PathVariable("id") String id) {
		return activityRepo.findById(id)
				.map(a -> ResponseEntity.ok(toSummary(a)))
				.orElse(ResponseEntity.notFound().build());
	}

	@GetMapping("/{id}/track")
	public ResponseEntity<StreamingResponseBody> track(@PathVariable("id") String id) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			Path cache = trackCache.getOrBuild(id, opt.get().getSourceFilename());
			StreamingResponseBody body = out -> Files.copy(cache, out);
			return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	@GetMapping("/{id}/track/chart")
	public ResponseEntity<StreamingResponseBody> chartTrack(@PathVariable("id") String id) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			Path cache = trackCache.getOrBuildChart(id, opt.get().getSourceFilename());
			StreamingResponseBody body = out -> Files.copy(cache, out);
			return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Raw per-point channels for the ride-detail page, columnar so a chart binds a
	 * channel directly. {@code channels} selects which (comma/space-separated:
	 * lat,lon,alt,hr,speed,cadence,power; default all). {@code reduced=true} (the
	 * default) serves the LTTB-reduced ride sidecar — the primary source the
	 * detail page renders; {@code reduced=false} serves the full-resolution cache.
	 */
	@GetMapping("/{id}/raw")
	public ResponseEntity<io.github.tourgaze.dto.RawTrackDto> raw(@PathVariable("id") String id,
			@RequestParam(value = "channels", required = false) String channels,
			@RequestParam(value = "reduced", defaultValue = "true") boolean reduced) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			// Primary source is the reduced ride sidecar; full-res only on demand.
			Path cache = reduced
					? trackCache.getOrBuildChart(id, opt.get().getSourceFilename())
					: trackCache.getOrBuild(id, opt.get().getSourceFilename());
			List<io.github.tourgaze.dto.TrackPointDto> pts = RAW_MAPPER.readValue(cache.toFile(),
					new com.fasterxml.jackson.core.type.TypeReference<List<io.github.tourgaze.dto.TrackPointDto>>() {
					});
			java.util.Set<String> want = (channels == null || channels.isBlank()) ? null
					: new java.util.HashSet<>(
							java.util.Arrays.asList(channels.toLowerCase(java.util.Locale.ROOT).split("[,\\s]+")));
			int n = pts.size();
			List<Double> lat = want == null || want.contains("lat") ? new java.util.ArrayList<>(n) : null;
			List<Double> lon = want == null || want.contains("lon") ? new java.util.ArrayList<>(n) : null;
			List<Double> alt = want == null || want.contains("alt") ? new java.util.ArrayList<>(n) : null;
			List<Integer> hr = want == null || want.contains("hr") ? new java.util.ArrayList<>(n) : null;
			List<Double> speed = want == null || want.contains("speed") ? new java.util.ArrayList<>(n) : null;
			List<Integer> cadence = want == null || want.contains("cadence") ? new java.util.ArrayList<>(n) : null;
			List<Integer> power = want == null || want.contains("power") ? new java.util.ArrayList<>(n) : null;
			for (io.github.tourgaze.dto.TrackPointDto p : pts) {
				if (lat != null)
					lat.add(p.lat());
				if (lon != null)
					lon.add(p.lon());
				if (alt != null)
					alt.add(p.altM());
				if (hr != null)
					hr.add(p.hr());
				if (speed != null)
					speed.add(p.speedMs());
				if (cadence != null)
					cadence.add(p.cadence());
				if (power != null)
					power.add(p.power());
			}
			return ResponseEntity.ok(new io.github.tourgaze.dto.RawTrackDto(n, reduced, lat, lon, alt, hr, speed,
					cadence, power));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	@PatchMapping("/{id}")
	@Transactional
	public ResponseEntity<ActivitySummaryDto> update(@PathVariable("id") String id,
			@RequestBody ActivityUpdateDto dto) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		Activity a = opt.get();

		if (dto.name() != null)
			a.setName(dto.name());
		// Store the chosen sport key as-is (it comes from the Sport lookup; custom
		// keys must survive, so we don't coerce through the old fixed enum).
		if (dto.activityType() != null && !dto.activityType().isBlank())
			a.setActivityType(dto.activityType().trim().toLowerCase());
		if (dto.description() != null)
			a.setDescription(dto.description());
		if (dto.attributes() != null)
			a.setAttributes(dto.attributes());
		if (dto.gearId() != null) {
			// Blank string = explicit "clear gear"; a real id assigns/changes it.
			if (dto.gearId().isBlank()) {
				a.setGear(null);
			} else {
				gearRepo.findById(dto.gearId()).ifPresent(a::setGear);
			}
		}
		if (dto.weatherTempC() != null)
			a.getWeather().setTempC(dto.weatherTempC());
		if (dto.weatherHumidityPct() != null)
			a.getWeather().setHumidityPct(dto.weatherHumidityPct());
		if (dto.weatherWindKph() != null)
			a.getWeather().setWindKph(dto.weatherWindKph());
		if (dto.weatherCondition() != null)
			a.getWeather().setCondition(dto.weatherCondition());
		if (dto.weightKg() != null) {
			boolean weightChanged = !java.util.Objects.equals(a.getWeightKg(), dto.weightKg());
			a.setWeightKg(dto.weightKg());
			// Keep the rider's profile (masterdata) body weight in sync: editing a
			// tour's weight is the natural place to record "this is what I weigh now".
			// Managed user → flushed on commit, no explicit save needed.
			if (weightChanged && a.getUser() != null) {
				a.getUser().setWeightKg(dto.weightKg());
			}
		}
		if (dto.startLocation() != null)
			a.setStartLocation(dto.startLocation());
		if (dto.startCountry() != null)
			a.setStartCountry(dto.startCountry().toUpperCase());
		if (dto.endLocation() != null)
			a.setEndLocation(dto.endLocation());
		if (dto.endCountry() != null)
			a.setEndCountry(dto.endCountry().toUpperCase());

		// tagIds non-null → replace the entire tag set (allows clearing via empty
		// list).
		if (dto.tagIds() != null) {
			List<Tag> tags = tagRepo.findAllById(dto.tagIds());
			a.setTags(new HashSet<>(tags));
		}

		Activity saved = activityRepo.save(a);
		events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(saved.getId()));
		return ResponseEntity.ok(toSummary(saved));
	}

	// ── Attributes (free-form key/value JSON) ────────────────────────────────
	// A ride carries an arbitrary attribute map (weather, coordinates, notes,
	// detected events, …). These endpoints make the API attribute-aware without a
	// schema change per key — send any JSON-shaped values.

	/** The activity's full attribute map. */
	@GetMapping("/{id}/attributes")
	public ResponseEntity<java.util.Map<String, Object>> getAttributes(@PathVariable("id") String id) {
		return activityRepo.findById(id)
				.map(a -> ResponseEntity.ok(a.getAttributes()))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** Replace the whole attribute map (send any JSON object). */
	@PutMapping("/{id}/attributes")
	@Transactional
	public ResponseEntity<java.util.Map<String, Object>> putAttributes(@PathVariable("id") String id,
			@RequestBody java.util.Map<String, Object> attributes) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		Activity a = opt.get();
		a.setAttributes(attributes);
		activityRepo.save(a);
		events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(id));
		return ResponseEntity.ok(a.getAttributes());
	}

	/** Set (or, with a null value, remove) a single attribute key, merging. */
	@PutMapping("/{id}/attributes/{key}")
	@Transactional
	public ResponseEntity<java.util.Map<String, Object>> putAttribute(@PathVariable("id") String id,
			@PathVariable("key") String key, @RequestBody(required = false) Object value) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		Activity a = opt.get();
		java.util.Map<String, Object> attrs = new java.util.HashMap<>(a.getAttributes());
		if (value == null)
			attrs.remove(key);
		else
			attrs.put(key, value);
		a.setAttributes(attrs);
		activityRepo.save(a);
		events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(id));
		return ResponseEntity.ok(a.getAttributes());
	}

	/**
	 * Typed view of the notable events recorded for this ride (rain showers, …).
	 */
	@GetMapping("/{id}/events")
	public ResponseEntity<List<io.github.tourgaze.dto.RideEventDto>> getEvents(@PathVariable("id") String id) {
		return activityRepo.findById(id)
				.map(a -> ResponseEntity.ok(toSummary(a).events()))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/** This ride's photos, geo-matched to the track (media.json manifest). */
	@GetMapping("/{id}/media")
	public ResponseEntity<List<io.github.tourgaze.service.MediaManifestService.MediaItem>> media(
			@PathVariable("id") String id) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok(mediaManifest.read(storage.activityMediaDir(opt.get().getSourceFilename())));
	}

	/** Upload personal photos onto an existing ride (EditTour drop area). */
	@PostMapping(value = "/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<List<String>> uploadMedia(@PathVariable("id") String id,
			@RequestParam("files") org.springframework.web.multipart.MultipartFile[] files) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			return ResponseEntity.ok(rideMedia.save(opt.get().getSourceFilename(), files));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/** Remove a photo from an existing ride. */
	@DeleteMapping("/{id}/media/{name}")
	public ResponseEntity<Void> deleteMedia(@PathVariable("id") String id, @PathVariable("name") String name) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			rideMedia.delete(opt.get().getSourceFilename(), name);
			return ResponseEntity.noContent().build();
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Move a discovered public photo into the user's personal set (img_public_ →
	 * img_personal_).
	 */
	@PostMapping("/{id}/media/{name}/personal")
	public ResponseEntity<List<io.github.tourgaze.service.MediaManifestService.MediaItem>> makePhotoPersonal(
			@PathVariable("id") String id, @PathVariable("name") String name) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			return ResponseEntity.ok(rideMedia.makePersonal(opt.get().getSourceFilename(), name));
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Find CC photos along the route (Wikimedia Commons) and add them to this ride.
	 */
	@PostMapping("/{id}/photos/discover")
	public ResponseEntity<?> discoverPhotos(@PathVariable("id") String id) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		try {
			return ResponseEntity.ok(wikimedia.discover(opt.get().getSourceFilename()));
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body(java.util.Map.of("error", String.valueOf(e.getMessage())));
		}
	}

	/** Serve one photo from a ride's media folder. */
	@GetMapping("/{id}/media/{name}")
	public ResponseEntity<byte[]> mediaFile(@PathVariable("id") String id, @PathVariable("name") String name) {
		if (name.contains("/") || name.contains("\\") || name.contains(".."))
			return ResponseEntity.badRequest().build();
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		java.nio.file.Path p = storage.activityMediaDir(opt.get().getSourceFilename()).resolve(name);
		if (!storage.encryptedExists(p))
			return ResponseEntity.notFound().build();
		try {
			return InboxController.serveImageBytes(storage.readEncrypted(p), name);
		} catch (IOException e) {
			return ResponseEntity.internalServerError().build();
		}
	}

	/**
	 * Rides that look like the same route (GPS overlap or shared tag) — compare
	 * picker.
	 */
	@GetMapping("/{id}/similar")
	public ResponseEntity<List<io.github.tourgaze.dto.SimilarRideDto>> similar(@PathVariable("id") String id) {
		if (!activityRepo.existsById(id))
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok(routeSimilarity.findSimilar(id));
	}

	/** A ride's recovery metadata (same shape as the store/ sidecar JSON). */
	@GetMapping("/{id}/metadata")
	public ResponseEntity<io.github.tourgaze.dto.RideMetadataDto> metadata(@PathVariable("id") String id) {
		return activityRepo.findById(id)
				.map(a -> ResponseEntity.ok(metadataMapper.toDto(a, java.time.Instant.now())))
				.orElse(ResponseEntity.notFound().build());
	}

	/**
	 * Bulk-assign (or clear) gear across many rides at once — the backfill path
	 * for tagging a whole history of rides with the bike they were ridden on.
	 * Blank/empty gearId clears gear on every listed ride.
	 */
	@PostMapping("/bulk-gear")
	@Transactional
	public ResponseEntity<java.util.Map<String, Object>> bulkSetGear(@RequestBody BulkGearRequest req) {
		if (req == null || req.ids() == null || req.ids().isEmpty()) {
			return ResponseEntity.ok(java.util.Map.of("updated", 0));
		}
		Gear gear = (req.gearId() != null && !req.gearId().isBlank())
				? gearRepo.findById(req.gearId()).orElse(null)
				: null;
		int n = 0;
		for (String id : req.ids()) {
			Optional<Activity> opt = activityRepo.findById(id);
			if (opt.isPresent()) {
				opt.get().setGear(gear);
				n++;
				events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Changed(id));
			}
		}
		return ResponseEntity.ok(java.util.Map.of("updated", n));
	}

	public record BulkGearRequest(List<String> ids, String gearId) {
	}

	@DeleteMapping("/{id}")
	@Transactional
	public ResponseEntity<Void> delete(@PathVariable("id") String id) {
		Optional<Activity> opt = activityRepo.findById(id);
		if (opt.isEmpty())
			return ResponseEntity.notFound().build();
		Activity a = opt.get();

		// Delete the ride's entire store/<id>/ folder (track + media + sidecar).
		// Non-fatal if it's gone or locked — the DB row is deleted below either way;
		// orphan bytes can be cleaned up by the storage-purge admin action.
		try {
			storage.deleteRide(a.getId());
		} catch (IOException e) {
			log.warn("Failed to delete ride folder for activity {}: {}", id, e.getMessage());
		}

		trackCache.evict(id);
		activityRepo.deleteById(id);
		events.publishEvent(new io.github.tourgaze.event.ActivityEvents.Removed(id, a.getSourceFilename()));
		return ResponseEntity.noContent().build();
	}

	private ActivitySummaryDto toSummary(Activity a) {
		return summaryMapper.toDto(a);
	}
}
