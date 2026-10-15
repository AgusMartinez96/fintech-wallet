package com.agustin.corebanking.operation;

import java.util.Map;
import java.util.UUID;

/**
 * Resultado de un paso. {@code replayed} indica que ya existía y se devolvió la respuesta guardada
 * (el mismo contenido que en la primera ejecución).
 */
public record OperationResult(UUID transferId, OperationStep step, OperationOutcome outcome,
		String replyRoutingKey, String replyType, Map<String, Object> payload, boolean replayed) {

	public boolean isSuccess() {
		return outcome == OperationOutcome.SUCCESS;
	}

	/** Motivo del rechazo ({@code INSUFFICIENT_FUNDS}, ...) o {@code null} si fue exitoso. */
	public String reason() {
		Object reason = payload.get("reason");
		return reason == null ? null : reason.toString();
	}

}
