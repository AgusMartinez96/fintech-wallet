package com.agustin.corebanking.deposit;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.account.AccountRepository;
import com.agustin.corebanking.common.Amounts;
import com.agustin.corebanking.common.BusinessException;
import com.agustin.corebanking.common.RequestHasher;
import com.agustin.corebanking.ledger.EntryType;
import com.agustin.corebanking.ledger.LedgerService;
import com.agustin.corebanking.ledger.ReferenceType;

import jakarta.persistence.EntityManager;

/**
 * Depósitos idempotentes de doble entrada: TREASURY (−) contra la cuenta (+). Orden de locks fijo
 * (cuenta del usuario → TREASURY) para que no haya ciclos; el lock de la cuenta también serializa
 * los reintentos concurrentes con la misma {@code Idempotency-Key}.
 */
@Service
public class DepositService {

	private static final int MAX_KEY_LENGTH = 64;

	public record DepositResult(UUID depositId, BigDecimal balance, boolean created) {
	}

	private final AccountRepository accounts;
	private final DepositRepository deposits;
	private final LedgerService ledger;
	private final EntityManager em;
	private final DepositProperties props;

	public DepositService(AccountRepository accounts, DepositRepository deposits, LedgerService ledger,
			EntityManager em, DepositProperties props) {
		this.accounts = accounts;
		this.deposits = deposits;
		this.ledger = ledger;
		this.em = em;
		this.props = props;
	}

	@Transactional
	public DepositResult demoDeposit(String userId, String idempotencyKey, BigDecimal rawAmount) {
		String key = requireKey(idempotencyKey);
		BigDecimal amount = Amounts.requireValid(rawAmount);
		if (amount.compareTo(props.demoMax()) > 0) {
			throw BusinessException.unprocessable("DEMO_LIMIT_EXCEEDED",
					"La carga demo no puede superar " + Amounts.format(props.demoMax()));
		}
		Account account = accounts.findByUserIdForUpdate(userId)
				.orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "Cuenta no encontrada"));
		return execute(account, userId, DepositSource.DEMO_FAUCET, key, amount);
	}

	@Transactional
	public DepositResult adminDeposit(String actorUserId, UUID accountId, String idempotencyKey, BigDecimal rawAmount) {
		String key = requireKey(idempotencyKey);
		BigDecimal amount = Amounts.requireValid(rawAmount);
		if (amount.compareTo(props.adminMax()) > 0) {
			throw BusinessException.unprocessable("AMOUNT_LIMIT_EXCEEDED", "El monto supera el máximo permitido");
		}
		Account account = accounts.findByIdForUpdate(accountId)
				.orElseThrow(() -> BusinessException.notFound("ACCOUNT_NOT_FOUND", "Cuenta no encontrada"));
		return execute(account, actorUserId, DepositSource.ADMIN, key, amount);
	}

	private DepositResult execute(Account account, String requestedBy, DepositSource source, String key,
			BigDecimal amount) {
		String hash = RequestHasher.sha256(source.name(), account.getId().toString(), Amounts.format(amount));

		// La idempotencia se resuelve antes de los topes: un reintento no debe fallar por el saldo ya cargado
		Optional<Deposit> existing = deposits.findByRequestedByAndIdempotencyKey(requestedBy, key);
		if (existing.isPresent()) {
			if (!existing.get().getRequestHash().equals(hash)) {
				throw BusinessException.unprocessable("IDEMPOTENCY_KEY_REUSED",
						"La Idempotency-Key ya se usó con otros datos");
			}
			return new DepositResult(existing.get().getId(), account.getBalance(), false);
		}

		if (account.isSystem()) {
			throw BusinessException.unprocessable("SYSTEM_ACCOUNT_IMMUTABLE",
					"Las cuentas de sistema no se pueden modificar");
		}
		if (!account.isActive()) {
			throw BusinessException.conflict("ACCOUNT_BLOCKED", "La cuenta está bloqueada");
		}
		if (source == DepositSource.DEMO_FAUCET
				&& account.getBalance().add(amount).compareTo(props.demoBalanceCap()) > 0) {
			throw BusinessException.unprocessable("BALANCE_CAP_EXCEEDED",
					"El saldo no puede superar " + Amounts.format(props.demoBalanceCap()) + " con cargas demo");
		}

		Account treasury = accounts.findByIdForUpdate(Account.TREASURY_ID)
				.orElseThrow(() -> new IllegalStateException("TREASURY account is missing"));

		Deposit deposit = new Deposit(account.getId(), requestedBy, source, key, hash, amount);
		em.persist(deposit);
		ledger.post(treasury, EntryType.DEPOSIT, ReferenceType.DEPOSIT, deposit.getId(), amount.negate(),
				account.getAlias());
		ledger.post(account, EntryType.DEPOSIT, ReferenceType.DEPOSIT, deposit.getId(), amount,
				treasury.getAlias());
		return new DepositResult(deposit.getId(), account.getBalance(), true);
	}

	private static String requireKey(String key) {
		if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
			throw BusinessException.badRequest("VALIDATION_ERROR",
					"Idempotency-Key es obligatoria (hasta " + MAX_KEY_LENGTH + " caracteres)");
		}
		return key;
	}

}
