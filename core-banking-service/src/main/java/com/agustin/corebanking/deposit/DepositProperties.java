package com.agustin.corebanking.deposit;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Topes de depósito (docs/05 §5.1): demo 50.000, saldo máximo con demo 200.000, ADMIN 1.000.000. */
@ConfigurationProperties("app.deposit")
public record DepositProperties(BigDecimal demoMax, BigDecimal demoBalanceCap, BigDecimal adminMax) {
}
