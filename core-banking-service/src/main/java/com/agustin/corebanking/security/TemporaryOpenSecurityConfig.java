package com.agustin.corebanking.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TEMPORAL (Fase 1): los starters de seguridad ya están en el pom, pero la seguridad real
 * (OAuth2/JWT, roles, propiedad por {@code sub}) es la Fase 2, que reemplaza esta clase.
 * No desplegar así.
 */
@Configuration
class TemporaryOpenSecurityConfig {

	@Bean
	SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
		return http.build();
	}

}
