package com.agustin.corebanking.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest request) {
		HttpStatus status = ex.getStatus();
		return ResponseEntity.status(status)
				.body(ApiError.of(status.value(), ex.getCode(), ex.getMessage(), request.getRequestURI()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		// Se registra la causa (sin datos del request); al cliente nunca se le devuelve la traza
		log.error("Unexpected error on {}", request.getRequestURI(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(500, "INTERNAL_ERROR",
				"Ocurrió un error inesperado", request.getRequestURI()));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
				.map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage())).toList();
		ApiError body = new ApiError(java.time.Instant.now(), 400, "VALIDATION_ERROR", "Solicitud inválida",
				path(request), fields);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		int status = statusCode.value();
		String code = switch (status) {
			case 400 -> "VALIDATION_ERROR";
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 415 -> "UNSUPPORTED_MEDIA_TYPE";
			default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_ERROR";
		};
		String message = status == 400 ? "Solicitud inválida" : "No se pudo procesar la solicitud";
		return ResponseEntity.status(statusCode).headers(headers)
				.body(ApiError.of(status, code, message, path(request)));
	}

	private static String path(WebRequest request) {
		return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "";
	}

}
