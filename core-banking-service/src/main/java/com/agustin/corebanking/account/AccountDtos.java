package com.agustin.corebanking.account;

import java.time.Instant;
import java.util.UUID;

import com.agustin.corebanking.common.Amounts;
import com.agustin.corebanking.crypto.AccountNumberMasker;
import com.agustin.corebanking.ledger.LedgerEntry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** DTOs de cuentas. Los montos viajan como string decimal (docs/05), nunca como número. */
public final class AccountDtos {

	private AccountDtos() {
	}

	public record AccountResponse(UUID id, String alias, String accountNumber, String currency, String balance,
			String status, boolean aliasEditable, Instant createdAt) {

		/** El dueño ve el número completo. */
		public static AccountResponse forOwner(Account a) {
			return of(a, a.getAccountNumber());
		}

		/** Cualquier otro lo ve enmascarado. */
		public static AccountResponse masked(Account a) {
			return of(a, AccountNumberMasker.mask(a.getAccountNumber()));
		}

		private static AccountResponse of(Account a, String number) {
			return new AccountResponse(a.getId(), a.getAlias(), number, a.getCurrency(),
					Amounts.format(a.getBalance()), a.getStatus().name(), !a.isAliasChanged(), a.getCreatedAt());
		}
	}

	public record AdminAccountResponse(UUID id, String userId, String type, String alias, String accountNumber,
			String currency, String balance, String status, Instant createdAt) {

		public static AdminAccountResponse from(Account a) {
			return new AdminAccountResponse(a.getId(), a.getUserId(), a.getType().name(), a.getAlias(),
					AccountNumberMasker.mask(a.getAccountNumber()), a.getCurrency(), Amounts.format(a.getBalance()),
					a.getStatus().name(), a.getCreatedAt());
		}
	}

	public record LookupResponse(UUID id, String alias) {

		public static LookupResponse from(Account a) {
			return new LookupResponse(a.getId(), a.getAlias());
		}
	}

	public record MovementResponse(UUID id, String entryType, String amount, String balanceAfter,
			String counterpartyAlias, String referenceType, UUID referenceId, Instant createdAt) {

		public static MovementResponse from(LedgerEntry e) {
			return new MovementResponse(e.getId(), e.getEntryType().name(), Amounts.format(e.getAmount()),
					Amounts.format(e.getBalanceAfter()), e.getCounterpartyAlias(), e.getReferenceType().name(),
					e.getReferenceId(), e.getCreatedAt());
		}
	}

	public record AliasRequest(@NotBlank String alias) {
	}

	public record StatusRequest(@NotNull AccountStatus status) {
	}

}
