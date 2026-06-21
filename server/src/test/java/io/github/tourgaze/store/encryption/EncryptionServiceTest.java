/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.store.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptionServiceTest {

	private final EncryptionService svc = new EncryptionService();

	private static byte[] key() {
		byte[] k = new byte[32];
		new SecureRandom().nextBytes(k);
		return k;
	}

	@Test
	void roundTripsAFile(@TempDir Path dir) throws IOException {
		byte[] key = key();
		byte[] plain = "a FIT file's bytes — lat/lon/hr/power…".repeat(50).getBytes(StandardCharsets.UTF_8);
		Path src = dir.resolve("ride.fit");
		Path enc = dir.resolve("ride.fit.enc");
		Files.write(src, plain);

		svc.encryptFile(src, enc, key);

		// On disk it's neither the plaintext nor the same length (IV + tag overhead).
		byte[] onDisk = Files.readAllBytes(enc);
		assertThat(onDisk).isNotEqualTo(plain);
		assertThat(onDisk.length).isEqualTo(plain.length + EncryptionService.OVERHEAD_BYTES);

		// Right key → exact plaintext back.
		assertThat(svc.decryptBytes(enc, key)).isEqualTo(plain);
	}

	@Test
	void wrongKeyIsRejected(@TempDir Path dir) throws IOException {
		Path src = dir.resolve("ride.fit");
		Path enc = dir.resolve("ride.fit.enc");
		Files.write(src, "secret track".getBytes(StandardCharsets.UTF_8));
		svc.encryptFile(src, enc, key());

		// GCM authentication: a different key must fail, not return garbage.
		assertThatThrownBy(() -> svc.decryptBytes(enc, key())).isInstanceOf(Exception.class);
	}
}
