/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.store.encryption;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

/**
 * AES-256-GCM file encryption (ported from matrosdms' EncryptionService).
 *
 * <p>
 * On-disk envelope: {@code [ 12-byte random IV ][ ciphertext ][ 16-byte GCM
 * tag ]}. The IV is generated per file with {@link SecureRandom} and written as
 * a plain prefix; the 128-bit GCM tag is appended automatically by the cipher
 * and authenticates the whole stream on decrypt (tamper-evident). Streaming, so
 * large rides never load fully into memory.
 *
 * <p>
 * The key itself is derived + held by {@link EncryptionConfig}; this class is a
 * pure, stateless cipher.
 */
@Service
public class EncryptionService {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int IV_LENGTH = 12; // GCM standard nonce
	private static final int TAG_LENGTH_BIT = 128;
	private static final int BUFFER = 8192;
	/** IV prefix (12) + GCM tag (16) added to every encrypted file. */
	public static final int OVERHEAD_BYTES = IV_LENGTH + 16;

	private final SecureRandom random = new SecureRandom();

	/** Encrypt {@code source} → {@code target} (IV-prefixed AES-GCM). */
	public void encryptFile(Path source, Path target, byte[] key) throws IOException {
		validateKey(key);
		byte[] iv = new byte[IV_LENGTH];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
			try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target), BUFFER)) {
				fos.write(iv); // plain IV prefix
				try (CipherOutputStream cos = new CipherOutputStream(fos, cipher);
						InputStream fis = new BufferedInputStream(Files.newInputStream(source), BUFFER)) {
					fis.transferTo(cos);
				}
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Encryption failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Open a decrypting stream over an encrypted file. Reads + verifies the IV
	 * prefix; the GCM tag is checked as the stream is consumed (a tampered or
	 * wrong-key file throws while reading).
	 */
	public InputStream decryptStream(Path encrypted, byte[] key) throws IOException {
		validateKey(key);
		InputStream fis = new BufferedInputStream(Files.newInputStream(encrypted), BUFFER);
		try {
			byte[] iv = fis.readNBytes(IV_LENGTH);
			if (iv.length != IV_LENGTH) {
				fis.close();
				throw new IOException("Not an encrypted file: missing IV");
			}
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
			return new CipherInputStream(fis, cipher);
		} catch (IOException e) {
			fis.close();
			throw e;
		} catch (Exception e) {
			fis.close();
			throw new IOException("Decryption failed: " + e.getMessage(), e);
		}
	}

	/** Encrypt in-memory {@code data} → {@code target} (IV-prefixed AES-GCM). */
	public void encryptBytes(byte[] data, Path target, byte[] key) throws IOException {
		validateKey(key);
		byte[] iv = new byte[IV_LENGTH];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LENGTH_BIT, iv));
			try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(target), BUFFER)) {
				fos.write(iv);
				try (CipherOutputStream cos = new CipherOutputStream(fos, cipher)) {
					cos.write(data);
				}
			}
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Encryption failed: " + e.getMessage(), e);
		}
	}

	/** Whole-file decrypt to bytes (small files: track JSON, media thumbnails). */
	public byte[] decryptBytes(Path encrypted, byte[] key) throws IOException {
		try (InputStream in = decryptStream(encrypted, key)) {
			return in.readAllBytes();
		}
	}

	private static void validateKey(byte[] key) {
		if (key == null || key.length != 32) {
			throw new IllegalArgumentException("AES-256 requires a 32-byte key");
		}
	}
}
