package com.agustin.corebanking.operation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Idempotencia por clave de negocio: una fila por {@code (transfer_id, step)} con la respuesta
 * original, que se re-emite si el comando llega de nuevo (aunque con otro messageId).
 */
@Entity
@Table(name = "transfer_operations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferOperation {

	@Id
	private UUID id;

	@Column(name = "transfer_id")
	private UUID transferId;

	@Enumerated(EnumType.STRING)
	private OperationStep step;

	@Enumerated(EnumType.STRING)
	private OperationOutcome outcome;

	@Column(precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(name = "from_account_id")
	private UUID fromAccountId;

	@Column(name = "to_account_id")
	private UUID toAccountId;

	@Column(name = "reply_routing_key")
	private String replyRoutingKey;

	@Column(name = "reply_type")
	private String replyType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "reply_payload", columnDefinition = "jsonb")
	private String replyPayload;

	@Column(name = "created_at")
	private Instant createdAt;

	TransferOperation(UUID transferId, OperationStep step, OperationOutcome outcome, BigDecimal amount,
			UUID fromAccountId, UUID toAccountId, String replyRoutingKey, String replyType, String replyPayload) {
		this.id = UUID.randomUUID();
		this.transferId = transferId;
		this.step = step;
		this.outcome = outcome;
		this.amount = amount;
		this.fromAccountId = fromAccountId;
		this.toAccountId = toAccountId;
		this.replyRoutingKey = replyRoutingKey;
		this.replyType = replyType;
		this.replyPayload = replyPayload;
		this.createdAt = Instant.now();
	}

}
