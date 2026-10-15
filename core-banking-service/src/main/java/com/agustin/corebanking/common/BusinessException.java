package com.agustin.corebanking.common;

import org.springframework.http.HttpStatus;

/** Error de regla de negocio o de recurso: se traduce a {@link ApiError} con el status indicado. */
public class BusinessException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public BusinessException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public static BusinessException badRequest(String code, String message) {
		return new BusinessException(HttpStatus.BAD_REQUEST, code, message);
	}

	public static BusinessException notFound(String code, String message) {
		return new BusinessException(HttpStatus.NOT_FOUND, code, message);
	}

	public static BusinessException conflict(String code, String message) {
		return new BusinessException(HttpStatus.CONFLICT, code, message);
	}

	public static BusinessException unprocessable(String code, String message) {
		return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
	}

}
