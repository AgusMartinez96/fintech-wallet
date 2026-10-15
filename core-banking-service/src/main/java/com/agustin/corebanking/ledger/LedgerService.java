package com.agustin.corebanking.ledger;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.agustin.corebanking.account.Account;

import jakarta.persistence.EntityManager;

/**
 * Único punto que mueve saldos. Exige una transacción en curso y una cuenta ya bloqueada
 * ({@code findByIdForUpdate}); actualiza el saldo y escribe el asiento con {@code balance_after}.
 */
@Service
public class LedgerService {

	private final EntityManager em;

	public LedgerService(EntityManager em) {
		this.em = em;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public LedgerEntry post(Account lockedAccount, EntryType entryType, ReferenceType referenceType,
			UUID referenceId, BigDecimal signedAmount, String counterpartyAlias) {
		BigDecimal balanceAfter = lockedAccount.applyDelta(signedAmount);
		LedgerEntry entry = new LedgerEntry(lockedAccount.getId(), entryType, referenceType, referenceId,
				signedAmount, balanceAfter, counterpartyAlias);
		em.persist(entry);
		return entry;
	}

}
