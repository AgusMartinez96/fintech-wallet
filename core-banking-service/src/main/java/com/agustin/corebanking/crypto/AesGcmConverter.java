package com.agustin.corebanking.crypto;

import java.util.Base64;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Cifra {@code accounts.account_number}. Los valores sin el prefijo {@code v1:} (p. ej. el
 * {@code SYSTEM-TREASURY} sembrado por Flyway, que no es un dato de usuario) se leen tal cual;
 * todo lo que se escribe siempre va cifrado.
 */
@Converter
@Component
public class AesGcmConverter implements AttributeConverter<String, String> {

	private final AesGcmCipher cipher;

	@Autowired
	public AesGcmConverter(CryptoProperties properties) {
		this(new AesGcmCipher(Base64.getDecoder().decode(properties.key())));
	}

	AesGcmConverter(AesGcmCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public String convertToDatabaseColumn(String attribute) {
		return attribute == null ? null : cipher.encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(String dbData) {
		if (dbData == null || !dbData.startsWith(AesGcmCipher.PREFIX)) {
			return dbData;
		}
		return cipher.decrypt(dbData);
	}

}
