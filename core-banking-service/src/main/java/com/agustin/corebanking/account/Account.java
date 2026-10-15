package com.agustin.corebanking.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.agustin.corebanking.crypto.AesGcmConverter;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

	public static final UUID TREASURY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Id
	private UUID id;

	@Column(name = "user_id")
	private String userId;

	@Enumerated(EnumType.STRING)
	private AccountType type;

	private String alias;

	@Column(name = "alias_changed")
	private boolean aliasChanged;

	@Convert(converter = AesGcmConverter.class)
	@Column(name = "account_number")
	private String accountNumber;

	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 3)
	private String currency;

	@Column(precision = 19, scale = 4)
	private BigDecimal balance;

	@Enumerated(EnumType.STRING)
	private AccountStatus status;

	@Version
	private Long version;

	@Column(name = "created_at")
	private Instant createdAt;

	public static Account newUser(String userId, String alias, String accountNumber) {
		Account a = new Account();
		a.id = UUID.randomUUID();
		a.userId = userId;
		a.type = AccountType.USER;
		a.alias = alias;
		a.aliasChanged = false;
		a.accountNumber = accountNumber;
		a.currency = "ARS";
		a.balance = BigDecimal.ZERO.setScale(4);
		a.status = AccountStatus.ACTIVE;
		a.createdAt = Instant.now();
		return a;
	}

	/** Suma un importe con signo. Solo el ledger la llama, con la cuenta ya bloqueada. */
	public BigDecimal applyDelta(BigDecimal delta) {
		BigDecimal next = balance.add(delta);
		if (type == AccountType.USER && next.signum() < 0) {
			throw new IllegalStateException("A USER account balance can never be negative");
		}
		balance = next;
		return next;
	}

	public void changeAlias(String newAlias) {
		this.alias = newAlias;
		this.aliasChanged = true;
	}

	public void changeStatus(AccountStatus newStatus) {
		this.status = newStatus;
	}

	public boolean isActive() {
		return status == AccountStatus.ACTIVE;
	}

	public boolean isSystem() {
		return type == AccountType.SYSTEM;
	}

}
