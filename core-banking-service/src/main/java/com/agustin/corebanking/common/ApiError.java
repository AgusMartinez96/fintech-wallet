package com.agustin.corebanking.common;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Formato de error uniforme (docs/05 §5). Nunca incluye trazas. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(Instant timestamp, int status, String code, String message, String path,
		List<FieldError> fieldErrors) {

	public record FieldError(String field, String message) {
	}

	public static ApiError of(int status, String code, String message, String path) {
		return new ApiError(Instant.now(), status, code, message, path, List.of());
	}

}
