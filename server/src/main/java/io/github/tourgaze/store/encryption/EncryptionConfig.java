/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.store.encryption;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns the at-rest encryption key for the store — derived once at startup from
 * an environment-provided password + salt (matrosdms model), held in memory
 * only, and wiped on shutdown. There is no interactive unlock: encryption is a
 * deployment choice driven by env vars.
 *
 * <p>
 * Opt-in via {@code tourgaze.store.cryptor} (env
 * {@code TOURGAZE_STORE_CRYPTOR}).
 * When unset/blank the store is plaintext and no key is derived. When set to an
 * AES mode, {@code tourgaze.store.password} and {@code tourgaze.store.salt} are
 * required — the app fails fast if they're missing, so you can never run
 * "encryption on, no key".
 *
 * <p>
 * <b>Warning:</b> the key is {@code Argon2id(password, salt)}; lose either and
 * the encrypted store is unrecoverable. There is deliberately no key escrow.
 */
@Component
public class EncryptionConfig {

	private static final Logger log = LoggerFactory.getLogger(EncryptionConfig.class);

	// Argon2id parameters (RFC 9106) — match matrosdms: t=3, m=64MB, p=1.
	private static final int ARGON2_ITERATIONS = 3;
	private static final int ARGON2_MEMORY_KB = 64 * 1024;
	private static final int ARGON2_PARALLELISM = 1;
	private static final int KEY_LENGTH_BYTES = 32; // AES-256

	private final String cryptor;
	private final String password;
	private final String salt;

	private volatile boolean enabled;
	private volatile byte[] key;

	public EncryptionConfig(
			@Value("${tourgaze.store.cryptor:}") String cryptor,
			@Value("${tourgaze.store.password:}") String password,
			@Value("${tourgaze.store.salt:}") String salt) {
		this.cryptor = cryptor == null ? "" : cryptor.trim();
		this.password = password == null ? "" : password;
		this.salt = salt == null ? "" : salt;
	}

	@PostConstruct
	void init() {
		String mode = cryptor.toUpperCase();
		this.enabled = mode.contains("AES") || mode.contains("GCM");
		if (!enabled) {
			log.info("[Encryption] store encryption OFF (plaintext) — set TOURGAZE_STORE_CRYPTOR=AES-GCM to enable");
			return;
		}
		if (password.isBlank() || salt.isBlank()) {
			throw new IllegalStateException(
					"Store encryption is enabled (tourgaze.store.cryptor) but password/salt are missing");
		}
		byte[] saltBytes = salt.getBytes(StandardCharsets.UTF_8);
		if (saltBytes.length < 8)
			log.warn("[Encryption] salt is short (<8 bytes) — use a longer tourgaze.store.salt");
		Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
				.withVersion(Argon2Parameters.ARGON2_VERSION_13)
				.withIterations(ARGON2_ITERATIONS)
				.withMemoryAsKB(ARGON2_MEMORY_KB)
				.withParallelism(ARGON2_PARALLELISM)
				.withSalt(saltBytes)
				.build();
		Argon2BytesGenerator gen = new Argon2BytesGenerator();
		gen.init(params);
		byte[] derived = new byte[KEY_LENGTH_BYTES];
		gen.generateBytes(password.toCharArray(), derived);
		this.key = derived;
		log.info("[Encryption] store encryption ON (AES-256-GCM, Argon2id key) — files written as *.enc");
	}

	@PreDestroy
	void wipe() {
		if (key != null) {
			Arrays.fill(key, (byte) 0);
			key = null;
		}
	}

	public boolean isEncryptionEnabled() {
		return enabled;
	}

	/** The in-memory AES key — never logged, never persisted. */
	public byte[] getKey() {
		return key;
	}

	/** {@code ".enc"} when encryption is on, else {@code ""}. */
	public String encryptedFileSuffix() {
		return enabled ? ".enc" : "";
	}
}
