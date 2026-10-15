package com.agustin.corebanking.deposit;

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

@Entity
@Table(name = "deposits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Deposit {

	@Id
	private UUID id;

	@Column(name = "account_id")
	private UUID accountId;

	@Column(name = "requested_by")
	private String requestedBy;

	@Enumerated(EnumType.STRING)
	private DepositSource source;

	@Column(name = "idempotency_key")
	private String idempotencyKey;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "request_hash", length = 64)
	private String requestHash;

	@Column(precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(name = "created_at")
	private Instant createdAt;

	Deposit(UUID accountId, String requestedBy, DepositSource source, String idempotencyKey, String requestHash,
			BigDecimal amount) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.requestedBy = requestedBy;
		this.source = source;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.amount = amount;
		this.createdAt = Instant.now();
	}

}
