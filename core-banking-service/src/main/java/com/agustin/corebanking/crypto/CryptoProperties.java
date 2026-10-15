package com.agustin.corebanking.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.crypto.key}: 32 bytes en base64, siempre por variable de entorno. */
@ConfigurationProperties("app.crypto")
public record CryptoProperties(String key) {
}
