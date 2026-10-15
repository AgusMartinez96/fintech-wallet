package com.agustin.corebanking.operation;

import java.math.BigDecimal;
import java.util.UUID;

/** Comandos de la Saga que Core ejecuta (docs/05 §6.3). Sin mensajería todavía: se invocan desde el servicio. */
public final class OperationCommands {

	private OperationCommands() {
	}

	public record DebitCommand(UUID transferId, String userId, UUID fromAccountId, UUID toAccountId,
			BigDecimal amount, String currency) {
	}

	public record CreditCommand(UUID transferId, UUID toAccountId, BigDecimal amount, String currency) {
	}

	public record RefundCommand(UUID transferId, UUID fromAccountId, BigDecimal amount, String reason) {
	}

}
