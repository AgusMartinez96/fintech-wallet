package com.agustin.corebanking.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.account.AccountRepository;
import com.agustin.corebanking.account.AccountService;
import com.agustin.corebanking.deposit.DepositService;
import com.agustin.corebanking.reconciliation.ReconciliationResponse;
import com.agustin.corebanking.reconciliation.ReconciliationService;

/** Helpers compartidos por los tests de integración. */
public class TestSupport {

	private final JdbcClient jdbc;
	private final AccountService accountService;
	private final AccountRepository accounts;
	private final DepositService deposits;
	private final ReconciliationService reconciliation;

	public TestSupport(JdbcClient jdbc, AccountService accountService, AccountRepository accounts,
			DepositService deposits, ReconciliationService reconciliation) {
		this.jdbc = jdbc;
		this.accountService = accountService;
		this.accounts = accounts;
		this.deposits = deposits;
		this.reconciliation = reconciliation;
	}

	/** Deja la base como recién migrada. TRUNCATE no dispara el trigger de inmutabilidad del ledger. */
	public void reset() {
		jdbc.sql("truncate table ledger_entries, transfer_operations, deposits, outbox_events, processed_messages, audit_log")
				.update();
		jdbc.sql("delete from accounts where type = 'USER'").update();
		jdbc.sql("update accounts set balance = 0, version = 0 where type = 'SYSTEM'").update();
	}

	public Account newAccount(String userId) {
		return accountService.createAccount(userId).account();
	}

	/** Cuenta con saldo, cargado por un depósito ADMIN real (así el ledger y TREASURY quedan consistentes). */
	public Account fundedAccount(String userId, String amount) {
		Account account = newAccount(userId);
		deposits.adminDeposit("test-admin", account.getId(), UUID.randomUUID().toString(), new BigDecimal(amount));
		return accounts.findById(account.getId()).orElseThrow();
	}

	public BigDecimal balance(UUID accountId) {
		return accounts.findById(accountId).orElseThrow().getBalance();
	}

	public void assertReconciliationOk() {
		ReconciliationResponse r = reconciliation.reconcile();
		assertThat(r.mismatches()).as("accounts whose balance differs from their ledger").isEmpty();
		assertThat(r.difference()).as("reconciliation difference").isEqualTo("0.00");
		assertThat(r.ok()).isTrue();
	}

	/** Lanza todas las tareas a la vez (barrera de arranque) y devuelve el resultado o la excepción de cada una. */
	public <T> List<Object> runConcurrently(int threads, List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					try {
						return task.call();
					} catch (Throwable t) {
						return t;
					}
				}));
			}
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> f : futures) {
				results.add(f.get());
			}
			return results;
		} finally {
			pool.shutdownNow();
		}
	}

}
