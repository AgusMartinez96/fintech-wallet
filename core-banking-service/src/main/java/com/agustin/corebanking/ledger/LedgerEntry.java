package com.agustin.corebanking.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Asiento inmutable (importe con signo). Nunca se actualiza ni se borra. */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

	@Id
	private UUID id;

	@Column(name = "account_id")
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reference_type")
	private ReferenceType referenceType;

	@Column(name = "reference_id")
	private UUID referenceId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type")
	private EntryType entryType;

	@Column(precision = 19, scale = 4)
	private BigDecimal amount;

	@Column(name = "balance_after", precision = 19, scale = 4)
	private BigDecimal balanceAfter;

	@Column(name = "counterparty_alias")
	private String counterpartyAlias;

	@Column(name = "created_at")
	private Instant createdAt;

	LedgerEntry(UUID accountId, EntryType entryType, ReferenceType referenceType, UUID referenceId,
			BigDecimal amount, BigDecimal balanceAfter, String counterpartyAlias) {
		this.id = UUID.randomUUID();
		this.accountId = accountId;
		this.entryType = entryType;
		this.referenceType = referenceType;
		this.referenceId = referenceId;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.counterpartyAlias = counterpartyAlias;
		this.createdAt = Instant.now();
	}

}
