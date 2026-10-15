package com.agustin.corebanking.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class AesGcmCipherTest {

	private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
	private static final String CBU = "2850590940090418135201";

	private final AesGcmCipher cipher = new AesGcmCipher(KEY);

	@Test
	void encryptThenDecryptReturnsOriginal() {
		String stored = cipher.encrypt(CBU);

		assertThat(stored).startsWith("v1:").doesNotContain(CBU);
		assertThat(cipher.decrypt(stored)).isEqualTo(CBU);
	}

	@Test
	void sameValueGetsDifferentIvEachTime() {
		assertThat(cipher.encrypt(CBU)).isNotEqualTo(cipher.encrypt(CBU));
	}

	@Test
	void tamperedCiphertextIsDetected() {
		byte[] raw = Base64.getDecoder().decode(cipher.encrypt(CBU).substring(3));
		raw[raw.length / 2] ^= 0x01;
		String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

		assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void wrongKeyCannotDecrypt() {
		AesGcmCipher other = new AesGcmCipher("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.US_ASCII));

		assertThatThrownBy(() -> other.decrypt(cipher.encrypt(CBU))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void keyMustBe32Bytes() {
		assertThatThrownBy(() -> new AesGcmCipher(new byte[16])).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void converterPassesThroughUnprefixedSystemValuesAndNulls() {
		AesGcmConverter converter = new AesGcmConverter(cipher);

		assertThat(converter.convertToEntityAttribute("SYSTEM-TREASURY")).isEqualTo("SYSTEM-TREASURY");
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToEntityAttribute(null)).isNull();
		assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(CBU))).isEqualTo(CBU);
	}

	@Test
	void masksAllButLastFourDigits() {
		assertThat(AccountNumberMasker.mask(CBU)).isEqualTo("****5201");
		assertThat(AccountNumberMasker.mask("12")).isEqualTo("****");
		assertThat(AccountNumberMasker.mask(null)).isEqualTo("****");
	}

}
