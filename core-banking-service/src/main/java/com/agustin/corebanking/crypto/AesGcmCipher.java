package com.agustin.corebanking.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM. Formato almacenado: {@code v1:} + base64(iv[12] || ciphertext || tag[16]).
 * El IV es aleatorio por valor; la manipulación del texto cifrado hace fallar el descifrado.
 */
public class AesGcmCipher {

	public static final String PREFIX = "v1:";

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final SecretKeySpec key;
	private final SecureRandom random = new SecureRandom();

	public AesGcmCipher(byte[] keyBytes) {
		if (keyBytes == null || keyBytes.length != 32) {
			throw new IllegalArgumentException("AES-256 key must be exactly 32 bytes");
		}
		this.key = new SecretKeySpec(keyBytes, "AES");
	}

	public String encrypt(String plain) {
		try {
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
			return PREFIX + Base64.getEncoder().encodeToString(out);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encryption failed", e);
		}
	}

	public String decrypt(String stored) {
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("Unsupported ciphertext format");
		}
		try {
			byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			if (in.length <= IV_BYTES) {
				throw new IllegalStateException("Ciphertext too short");
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
			return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			// Mensaje genérico: nunca incluir el valor ni la clave
			throw new IllegalStateException("Decryption failed (wrong key or tampered data)", e);
		}
	}

}
