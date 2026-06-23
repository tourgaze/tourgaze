/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.tourgaze.entity.Activity;
import io.github.tourgaze.entity.Gear;
import io.github.tourgaze.entity.User;
import io.github.tourgaze.repository.ActivityRepository;
import io.github.tourgaze.repository.GearRepository;
import io.github.tourgaze.repository.UserRepository;
import io.github.tourgaze.store.StorageService;

/**
 * End-to-end guard for disaster recovery: seed a rider + gear + a ride (with a
 * real GPX in the store), export the sidecars, wipe the DB, and prove
 * {@link RideRecoveryService} rebuilds everything from the store alone — rides
 * with their metadata + events, the rider profile, and the full gear list, with
 * the links restored. This is the regression net for the sidecar format and the
 * export/recover round-trip.
 */
@SpringBootTest
class RideRecoveryServiceTest {

	// Isolated store/db so the test never touches the real ~/.tourgaze.
	@TempDir
	static Path tmp;

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry reg) {
		reg.add("tourgaze.data-dir", () -> tmp.resolve("data").toString().replace('\\', '/'));
		reg.add("tourgaze.repository-dir", () -> tmp.resolve("repo").toString().replace('\\', '/'));
	}

	private static final String GPX = """
			<?xml version="1.0" encoding="UTF-8"?>
			<gpx version="1.1" creator="tourgaze-test" xmlns="http://www.topografix.com/GPX/1/1">
			  <trk><trkseg>
			    <trkpt lat="47.2000" lon="11.4000"><ele>500</ele><time>2026-01-01T10:00:00Z</time></trkpt>
			    <trkpt lat="47.2010" lon="11.4010"><ele>510</ele><time>2026-01-01T10:00:30Z</time></trkpt>
			    <trkpt lat="47.2020" lon="11.4020"><ele>520</ele><time>2026-01-01T10:01:00Z</time></trkpt>
			  </trkseg></trk>
			</gpx>
			""";

	@Autowired
	StorageService storage;
	@Autowired
	RideExportService rideExport;
	@Autowired
	RideRecoveryService rideRecovery;
	@Autowired
	ActivityRepository activityRepo;
	@Autowired
	UserRepository userRepo;
	@Autowired
	GearRepository gearRepo;

	@Test
	void exportThenWipeThenRecoverRestoresEverything() throws Exception {
		// ── Seed: rider, two bikes (one used on the ride, one not), one ride. ──
		User martin = new User();
		martin.setUsername("martin");
		martin.setDisplayName("Martin");
		martin.setWeightKg(73.0);
		martin = userRepo.save(martin);

		Gear used = new Gear();
		used.setName("Hibike");
		used.setType("bike");
		used.setDescription("MTB");
		used.setWeightKg(11.5);
		used = gearRepo.save(used);

		Gear unused = new Gear();
		unused.setName("Lapiere");
		unused.setType("bike");
		unused.setDescription("Racebike");
		gearRepo.save(unused); // attached to no ride — only the library sidecar saves it

		Activity a = new Activity();
		String id = io.github.tourgaze.util.ShortId.next();
		a.setId(id);
		String rel = id + "/ride.gpx";
		Path tmpFile = Files.createTempFile("ride", ".gpx");
		Files.writeString(tmpFile, GPX);
		storage.moveIntoStore(tmpFile, rel);
		a.setSourceFilename(rel);
		a.setSourceFormat("gpx");
		a.setSourceHash("hash-" + id);
		a.setName("Test Ride");
		a.setDescription("A".repeat(300)); // >255 chars: needs the text column
		a.setImportedAt(java.time.Instant.now());
		a.setUser(martin);
		a.setGear(used);
		a.setAttributes(Map.of("events", List.of(Map.of(
				"type", "PUNCTURE", "label", "Reifen Defekt", "lat", 47.201, "lon", 11.401))));
		activityRepo.save(a);

		// ── Export sidecars (rides + library), then simulate a lost DB. ──
		rideExport.exportAllNow();
		assertThat(storage.storeDir().resolve("library.metadata.json")).exists();

		activityRepo.deleteAll();
		gearRepo.deleteAll();
		userRepo.deleteAll();
		assertThat(activityRepo.count()).isZero();

		// ── Recover from the store alone. ──
		RideRecoveryService.RecoveryReport report = rideRecovery.recoverAll();

		assertThat(report.recovered()).isEqualTo(1);
		assertThat(report.failed()).isZero();
		assertThat(report.usersRestored()).isEqualTo(1);
		assertThat(report.gearRestored()).isEqualTo(2);

		// Rider restored with full profile.
		List<User> users = userRepo.findAll();
		assertThat(users).hasSize(1);
		assertThat(users.get(0).getDisplayName()).isEqualTo("Martin");
		assertThat(users.get(0).getWeightKg()).isEqualTo(73.0);

		// Both bikes restored (incl. the one on no ride), with full details.
		assertThat(gearRepo.findAll()).extracting(Gear::getName)
				.containsExactlyInAnyOrder("Hibike", "Lapiere");

		// Ride restored with long description, event, and rider/gear links.
		// (Resolve linked rows via the repos so we don't traverse lazy proxies
		// outside a session — proxy getId() is safe, getName() would not be.)
		Activity r = activityRepo.findById(id).orElseThrow();
		assertThat(r.getName()).isEqualTo("Test Ride");
		assertThat(r.getDescription()).hasSize(300);
		assertThat(r.getUser()).isNotNull();
		assertThat(userRepo.findById(r.getUser().getId()).orElseThrow().getDisplayName()).isEqualTo("Martin");
		assertThat(r.getGear()).isNotNull();
		Gear linked = gearRepo.findById(r.getGear().getId()).orElseThrow();
		assertThat(linked.getName()).isEqualTo("Hibike");
		assertThat(linked.getWeightKg()).isEqualTo(11.5);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> events = (List<Map<String, Object>>) r.getAttributes().get("events");
		assertThat(events).hasSize(1);
		assertThat(events.get(0)).containsEntry("type", "PUNCTURE").containsEntry("label", "Reifen Defekt");

		// Idempotent: a second pass recovers nothing new.
		RideRecoveryService.RecoveryReport again = rideRecovery.recoverAll();
		assertThat(again.recovered()).isZero();
		assertThat(again.skipped()).isEqualTo(1);
	}
}
