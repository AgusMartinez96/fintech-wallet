package com.agustin.corebanking.common;

import java.util.TimeZone;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Todo el sistema trabaja en UTC (docs/05). Además el driver de Postgres manda la zona del JVM
 * como parámetro de sesión, y alias viejos como {@code America/Buenos_Aires} (Windows) hacen que
 * Postgres 17 rechace la conexión. Corre antes de crear el pool de conexiones y de Flyway.
 * Registrado en {@code META-INF/spring.factories}.
 */
public class UtcTimeZoneInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

}
