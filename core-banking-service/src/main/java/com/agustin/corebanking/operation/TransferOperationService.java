package com.agustin.corebanking.operation;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.account.AccountRepository;
import com.agustin.corebanking.common.Amounts;
import com.agustin.corebanking.ledger.EntryType;
import com.agustin.corebanking.ledger.LedgerService;
import com.agustin.corebanking.ledger.ReferenceType;
import com.agustin.corebanking.operation.OperationCommands.CreditCommand;
import com.agustin.corebanking.operation.OperationCommands.DebitCommand;
import com.agustin.corebanking.operation.OperationCommands.RefundCommand;

import jakarta.persistence.EntityManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pasos de la Saga en Core (docs/05 §1.3). Cada paso es una transacción que toca UNA sola cuenta
 * (por eso no hay deadlock entre cuentas) y es idempotente por {@code (transfer_id, step)}: si ya
 * existe, devuelve la respuesta guardada sin volver a ejecutar. Los rechazos de negocio se
 * persisten como {@code REJECTED}; las violaciones de precondición lanzan excepción.
 * <p>
 * Fase 1: sin mensajería. La respuesta queda en {@code transfer_operations}; en la Fase 3 se
 * publica por el outbox.
 */
@Service
public class TransferOperationService {

	static final String RK_DEBITED = "tx.reply.core.debited";
	static final String RK_DEBIT_REJECTED = "tx.reply.core.debit-rejected";
	static final String RK_CREDITED = "tx.reply.core.credited";
	static final String RK_CREDIT_FAILED = "tx.reply.core.credit-failed";
	static final String RK_REFUNDED = "tx.reply.core.refunded";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final AccountRepository accounts;
	private final TransferOperationRepository operations;
	private final LedgerService ledger;
	private final EntityManager em;
	private final JsonMapper json;

	public TransferOperationService(AccountRepository accounts, TransferOperationRepository operations,
			LedgerService ledger, EntityManager em, JsonMapper json) {
		this.accounts = accounts;
		this.operations = operations;
		this.ledger = ledger;
		this.em = em;
		this.json = json;
	}

	@Transactional
	public OperationResult debit(DebitCommand cmd) {
		requirePositive(cmd.amount());
		Optional<TransferOperation> existing = operations.findByTransferIdAndStep(cmd.transferId(), OperationStep.DEBIT);
		if (existing.isPresent()) {
			return replay(existing.get());
		}

		Optional<Account> fromOpt = accounts.findByIdForUpdate(cmd.fromAccountId());

		// Re-chequeo con la cuenta bloqueada: un duplicado concurrente ya pudo haber terminado
		existing = operations.findByTransferIdAndStep(cmd.transferId(), OperationStep.DEBIT);
		if (existing.isPresent()) {
			return replay(existing.get());
		}

		if (fromOpt.isEmpty()) {
			return rejectDebit(cmd, "ACCOUNT_NOT_FOUND");
		}
		Account from = fromOpt.get();
		if (!Objects.equals(from.getUserId(), cmd.userId())) {
			return rejectDebit(cmd, "NOT_OWNER");
		}
		if (!from.isActive()) {
			return rejectDebit(cmd, "ACCOUNT_BLOCKED");
		}
		if (!from.getCurrency().equals(cmd.currency())) {
			return rejectDebit(cmd, "CURRENCY_MISMATCH");
		}
		if (from.getBalance().compareTo(cmd.amount()) < 0) {
			return rejectDebit(cmd, "INSUFFICIENT_FUNDS");
		}
		// El destino se lee sin bloquear: solo se valida que exista y esté activo
		Optional<Account> to = accounts.findById(cmd.toAccountId()).filter(Account::isActive);
		if (to.isEmpty()) {
			return rejectDebit(cmd, "DESTINATION_UNAVAILABLE");
		}

		ledger.post(from, EntryType.DEBIT, ReferenceType.TRANSFER, cmd.transferId(), cmd.amount().negate(),
				to.get().getAlias());

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("fromAccountId", from.getId());
		payload.put("amount", Amounts.format(cmd.amount()));
		payload.put("balanceAfter", Amounts.format(from.getBalance()));
		return save(cmd.transferId(), OperationStep.DEBIT, OperationOutcome.SUCCESS, cmd.amount(),
				cmd.fromAccountId(), cmd.toAccountId(), RK_DEBITED, "FundsDebited", payload);
	}

	@Transactional
	public OperationResult credit(CreditCommand cmd) {
		requirePositive(cmd.amount());
		// Misma fila DEBIT que bloquea el refund: crédito y refund de una transferencia se serializan
		TransferOperation debit = operations.findForUpdate(cmd.transferId(), OperationStep.DEBIT)
				.orElseThrow(() -> new IllegalStateException("No DEBIT operation for transfer " + cmd.transferId()));
		if (debit.getOutcome() != OperationOutcome.SUCCESS) {
			throw new IllegalStateException("DEBIT was rejected for transfer " + cmd.transferId());
		}
		Optional<TransferOperation> existing = operations.findByTransferIdAndStep(cmd.transferId(), OperationStep.CREDIT);
		if (existing.isPresent()) {
			return replay(existing.get());
		}
		requireSameAsDebit(debit, cmd.amount());
		if (!debit.getToAccountId().equals(cmd.toAccountId())) {
			throw new IllegalArgumentException("Credit destination differs from the debited transfer");
		}

		Optional<Account> toOpt = accounts.findByIdForUpdate(debit.getToAccountId());
		if (toOpt.isEmpty()) {
			return rejectCredit(debit, "DESTINATION_NOT_FOUND");
		}
		Account to = toOpt.get();
		if (!to.isActive()) {
			return rejectCredit(debit, "DESTINATION_BLOCKED");
		}

		String fromAlias = accounts.findById(debit.getFromAccountId()).map(Account::getAlias).orElse(null);
		ledger.post(to, EntryType.CREDIT, ReferenceType.TRANSFER, cmd.transferId(), cmd.amount(), fromAlias);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("toAccountId", to.getId());
		payload.put("amount", Amounts.format(cmd.amount()));
		payload.put("balanceAfter", Amounts.format(to.getBalance()));
		return save(cmd.transferId(), OperationStep.CREDIT, OperationOutcome.SUCCESS, cmd.amount(),
				debit.getFromAccountId(), debit.getToAccountId(), RK_CREDITED, "FundsCredited", payload);
	}

	/** Devuelve el dinero al origen tras un crédito rechazado. Ignora si la cuenta está BLOCKED. */
	@Transactional
	public OperationResult refund(RefundCommand cmd) {
		requirePositive(cmd.amount());
		TransferOperation debit = operations.findForUpdate(cmd.transferId(), OperationStep.DEBIT)
				.orElseThrow(() -> new IllegalStateException("No DEBIT operation for transfer " + cmd.transferId()));
		if (debit.getOutcome() != OperationOutcome.SUCCESS) {
			throw new IllegalStateException("DEBIT was rejected for transfer " + cmd.transferId());
		}
		Optional<TransferOperation> existing = operations.findByTransferIdAndStep(cmd.transferId(), OperationStep.REFUND);
		if (existing.isPresent()) {
			return replay(existing.get());
		}
		TransferOperation credit = operations.findByTransferIdAndStep(cmd.transferId(), OperationStep.CREDIT)
				.orElseThrow(() -> new IllegalStateException("Refund requires a rejected CREDIT for " + cmd.transferId()));
		if (credit.getOutcome() != OperationOutcome.REJECTED) {
			throw new IllegalStateException("Refund not allowed: CREDIT succeeded for " + cmd.transferId());
		}
		requireSameAsDebit(debit, cmd.amount());
		if (!debit.getFromAccountId().equals(cmd.fromAccountId())) {
			throw new IllegalArgumentException("Refund account differs from the debited account");
		}

		// No se valida el status: un refund siempre se aplica, aunque el origen se haya bloqueado
		Account from = accounts.findByIdForUpdate(debit.getFromAccountId())
				.orElseThrow(() -> new IllegalStateException("Refund account missing for " + cmd.transferId()));
		String toAlias = accounts.findById(debit.getToAccountId()).map(Account::getAlias).orElse(null);
		ledger.post(from, EntryType.REVERSAL, ReferenceType.TRANSFER, cmd.transferId(), cmd.amount(), toAlias);

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("fromAccountId", from.getId());
		payload.put("amount", Amounts.format(cmd.amount()));
		payload.put("balanceAfter", Amounts.format(from.getBalance()));
		return save(cmd.transferId(), OperationStep.REFUND, OperationOutcome.SUCCESS, cmd.amount(),
				debit.getFromAccountId(), debit.getToAccountId(), RK_REFUNDED, "FundsRefunded", payload);
	}

	private OperationResult rejectDebit(DebitCommand cmd, String reason) {
		return save(cmd.transferId(), OperationStep.DEBIT, OperationOutcome.REJECTED, cmd.amount(),
				cmd.fromAccountId(), cmd.toAccountId(), RK_DEBIT_REJECTED, "DebitRejected", Map.of("reason", reason));
	}

	private OperationResult rejectCredit(TransferOperation debit, String reason) {
		return save(debit.getTransferId(), OperationStep.CREDIT, OperationOutcome.REJECTED, debit.getAmount(),
				debit.getFromAccountId(), debit.getToAccountId(), RK_CREDIT_FAILED, "CreditFailed",
				Map.of("reason", reason));
	}

	private OperationResult save(UUID transferId, OperationStep step, OperationOutcome outcome, BigDecimal amount,
			UUID fromAccountId, UUID toAccountId, String routingKey, String type, Map<String, Object> payload) {
		em.persist(new TransferOperation(transferId, step, outcome, amount, fromAccountId, toAccountId, routingKey,
				type, json.writeValueAsString(payload)));
		return new OperationResult(transferId, step, outcome, routingKey, type, normalize(payload), false);
	}

	private OperationResult replay(TransferOperation op) {
		return new OperationResult(op.getTransferId(), op.getStep(), op.getOutcome(), op.getReplyRoutingKey(),
				op.getReplyType(), json.readValue(op.getReplyPayload(), MAP_TYPE), true);
	}

	/** Mismo formato (todo texto) que tras leerlo del JSON guardado. */
	private Map<String, Object> normalize(Map<String, Object> payload) {
		return json.readValue(json.writeValueAsString(payload), MAP_TYPE);
	}

	private static void requirePositive(BigDecimal amount) {
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalArgumentException("Amount must be positive");
		}
	}

	private static void requireSameAsDebit(TransferOperation debit, BigDecimal amount) {
		if (debit.getAmount().compareTo(amount) != 0) {
			throw new IllegalArgumentException("Amount differs from the debited amount");
		}
	}

}
