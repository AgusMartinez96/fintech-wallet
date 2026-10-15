package com.agustin.corebanking.account;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.agustin.corebanking.common.BusinessException;
import com.agustin.corebanking.ledger.LedgerEntry;
import com.agustin.corebanking.ledger.LedgerRepository;

@Service
public class AccountService {

	private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-z0-9._-]{3,30}$");
	private static final String ALIAS_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
	private static final int CREATE_ATTEMPTS = 5;

	private final AccountRepository accounts;
	private final LedgerRepository ledger;
	private final TransactionTemplate tx;
	private final SecureRandom random = new SecureRandom();

	public AccountService(AccountRepository accounts, LedgerRepository ledger, PlatformTransactionManager txManager) {
		this.accounts = accounts;
		this.ledger = ledger;
		this.tx = new TransactionTemplate(txManager);
	}

	public record CreateResult(Account account, boolean created) {
	}

	/**
	 * Crea la cuenta del usuario o devuelve la existente. Dos pedidos en paralelo terminan con
	 * una sola fila: el índice único parcial por {@code user_id} rechaza al perdedor, que relee.
	 */
	public CreateResult createAccount(String userId) {
		for (int attempt = 0; attempt < CREATE_ATTEMPTS; attempt++) {
			Optional<Account> existing = accounts.findByUserId(userId);
			if (existing.isPresent()) {
				return new CreateResult(existing.get(), false);
			}
			try {
				Account created = tx.execute(status -> accounts
						.saveAndFlush(Account.newUser(userId, randomAlias(), randomAccountNumber())));
				return new CreateResult(created, true);
			} catch (DataIntegrityViolationException e) {
				// Otro pedido creó la cuenta, o el alias generado chocó: se relee / reintenta
			}
		}
		throw new IllegalStateException("Could not create the account after " + CREATE_ATTEMPTS + " attempts");
	}

	@Transactional(readOnly = true)
	public Account getByUserId(String userId) {
		return accounts.findByUserId(userId).orElseThrow(AccountService::notFound);
	}

	@Transactional
	public Account changeAlias(String userId, String rawAlias) {
		String alias = normalizeAlias(rawAlias);
		if (!ALIAS_PATTERN.matcher(alias).matches()) {
			throw BusinessException.unprocessable("ALIAS_INVALID",
					"El alias debe tener entre 3 y 30 caracteres: letras minúsculas, números, punto, guion o guion bajo");
		}
		Account account = accounts.findByUserIdForUpdate(userId).orElseThrow(AccountService::notFound);
		if (account.isAliasChanged()) {
			throw BusinessException.conflict("ALIAS_ALREADY_CHANGED", "El alias ya fue modificado una vez");
		}
		accounts.findByAlias(alias).filter(other -> !other.getId().equals(account.getId())).ifPresent(other -> {
			throw BusinessException.conflict("ALIAS_TAKEN", "Ese alias ya está en uso");
		});
		account.changeAlias(alias);
		try {
			return accounts.saveAndFlush(account);
		} catch (DataIntegrityViolationException e) {
			throw BusinessException.conflict("ALIAS_TAKEN", "Ese alias ya está en uso");
		}
	}

	/** Destino de una transferencia: solo cuentas USER activas (si no, 404 sin revelar el motivo). */
	@Transactional(readOnly = true)
	public Account lookup(String rawAlias) {
		return accounts.findByAlias(normalizeAlias(rawAlias))
				.filter(a -> a.getType() == AccountType.USER && a.isActive())
				.orElseThrow(AccountService::notFound);
	}

	@Transactional
	public Account changeStatus(UUID accountId, AccountStatus status) {
		Account account = accounts.findByIdForUpdate(accountId).orElseThrow(AccountService::notFound);
		if (account.isSystem()) {
			throw BusinessException.unprocessable("SYSTEM_ACCOUNT_IMMUTABLE",
					"Las cuentas de sistema no se pueden modificar");
		}
		account.changeStatus(status);
		return accounts.saveAndFlush(account);
	}

	@Transactional(readOnly = true)
	public Page<Account> listForAdmin(AccountStatus status, Pageable pageable) {
		return status == null ? accounts.findAll(pageable) : accounts.findByStatus(status, pageable);
	}

	/** Movimientos de una cuenta propia. Una cuenta ajena responde 404, igual que una inexistente. */
	@Transactional(readOnly = true)
	public Page<LedgerEntry> movements(String userId, UUID accountId, Pageable pageable) {
		accounts.findById(accountId).filter(a -> userId.equals(a.getUserId())).orElseThrow(AccountService::notFound);
		return ledger.findByAccountIdOrderByCreatedAtDesc(accountId, pageable);
	}

	private static BusinessException notFound() {
		return BusinessException.notFound("ACCOUNT_NOT_FOUND", "Cuenta no encontrada");
	}

	private static String normalizeAlias(String alias) {
		return alias == null ? "" : alias.trim().toLowerCase(Locale.ROOT);
	}

	private String randomAlias() {
		StringBuilder sb = new StringBuilder("wallet.");
		for (int i = 0; i < 6; i++) {
			sb.append(ALIAS_ALPHABET.charAt(random.nextInt(ALIAS_ALPHABET.length())));
		}
		return sb.toString();
	}

	/** CBU simulado: 22 dígitos aleatorios (sin dígito verificador real). */
	private String randomAccountNumber() {
		StringBuilder sb = new StringBuilder(22);
		for (int i = 0; i < 22; i++) {
			sb.append(random.nextInt(10));
		}
		return sb.toString();
	}

}
