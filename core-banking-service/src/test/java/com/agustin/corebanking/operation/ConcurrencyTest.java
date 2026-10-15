package com.agustin.corebanking.operation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.account.AccountService;
import com.agustin.corebanking.account.AccountStatus;
import com.agustin.corebanking.deposit.DepositService;
import com.agustin.corebanking.deposit.DepositService.DepositResult;
import com.agustin.corebanking.ledger.EntryType;
import com.agustin.corebanking.ledger.LedgerEntry;
import com.agustin.corebanking.ledger.LedgerRepository;
import com.agustin.corebanking.operation.OperationCommands.CreditCommand;
import com.agustin.corebanking.operation.OperationCommands.DebitCommand;
import com.agustin.corebanking.operation.OperationCommands.RefundCommand;
import com.agustin.corebanking.support.IntegrationTest;
import com.agustin.corebanking.support.TestSupport;

/** docs/07 §2.3: el dinero tiene que salir exacto bajo concurrencia real, contra Postgres real. */
@IntegrationTest
class ConcurrencyTest {

	@Autowired
	TransferOperationService service;
	@Autowired
	DepositService deposits;
	@Autowired
	AccountService accountService;
	@Autowired
	LedgerRepository ledger;
	@Autowired
	TestSupport support;

	@BeforeEach
	void setUp() {
		support.reset();
	}

	private static BigDecimal amt(String v) {
		return new BigDecimal(v);
	}

	private static void assertNoFailures(List<Object> results) {
		assertThat(results).noneMatch(r -> r instanceof Throwable);
	}

	private long count(List<LedgerEntry> entries, EntryType type) {
		return entries.stream().filter(e -> e.getEntryType() == type).count();
	}

	@Test
	void fiftyParallelDebitsOnOneAccountNeverOverdrawAndTheBalanceIsExact() throws Exception {
		Account alice = support.fundedAccount("alice", "30000");
		Account bob = support.newAccount("bob");
		List<Callable<OperationResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			UUID transferId = UUID.randomUUID();
			tasks.add(() -> service.debit(
					new DebitCommand(transferId, "alice", alice.getId(), bob.getId(), amt("1000"), "ARS")));
		}

		List<Object> results = support.runConcurrently(50, tasks);

		assertNoFailures(results);
		long ok = results.stream().filter(r -> ((OperationResult) r).isSuccess()).count();
		long insufficient = results.stream()
				.filter(r -> "INSUFFICIENT_FUNDS".equals(((OperationResult) r).reason())).count();
		assertThat(ok).isEqualTo(30);
		assertThat(insufficient).isEqualTo(20);
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("0");
		assertThat(count(ledger.findByAccountIdOrderByCreatedAtAsc(alice.getId()), EntryType.DEBIT)).isEqualTo(30);
		support.assertReconciliationOk();
	}

	@Test
	void crossedTransfersBetweenTwoAccountsDoNotDeadlockAndKeepTheTotal() throws Exception {
		Account a = support.fundedAccount("alice", "100000");
		Account b = support.fundedAccount("bob", "100000");
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			tasks.add(() -> transfer("alice", a, b, "100"));
			tasks.add(() -> transfer("bob", b, a, "100"));
		}

		List<Object> results = support.runConcurrently(40, tasks);

		assertNoFailures(results);
		assertThat(results).allMatch(r -> Boolean.TRUE.equals(r));
		assertThat(support.balance(a.getId())).isEqualByComparingTo("100000");
		assertThat(support.balance(b.getId())).isEqualByComparingTo("100000");
		support.assertReconciliationOk();
	}

	private boolean transfer(String userId, Account from, Account to, String amount) {
		UUID transferId = UUID.randomUUID();
		OperationResult debit = service.debit(new DebitCommand(transferId, userId, from.getId(), to.getId(), amt(amount), "ARS"));
		if (!debit.isSuccess()) {
			return false;
		}
		return service.credit(new CreditCommand(transferId, to.getId(), amt(amount), "ARS")).isSuccess();
	}

	@Test
	void fiftyOriginsCreditingTheSameDestinationConcurrentlyYieldTheExactSum() throws Exception {
		Account target = support.newAccount("target");
		List<Account> origins = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			origins.add(support.fundedAccount("origin" + i, "1000"));
		}
		List<Callable<Boolean>> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			Account origin = origins.get(i);
			String userId = "origin" + i;
			tasks.add(() -> transfer(userId, origin, target, "1000"));
		}

		List<Object> results = support.runConcurrently(50, tasks);

		assertNoFailures(results);
		assertThat(support.balance(target.getId())).isEqualByComparingTo("50000");
		assertThat(count(ledger.findByAccountIdOrderByCreatedAtAsc(target.getId()), EntryType.CREDIT)).isEqualTo(50);
		support.assertReconciliationOk();
	}

	@Test
	void theSameDebitCommandDeliveredConcurrentlyAppliesExactlyOnce() throws Exception {
		Account alice = support.fundedAccount("alice", "10000");
		Account bob = support.newAccount("bob");
		UUID transferId = UUID.randomUUID();
		List<Callable<OperationResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			tasks.add(() -> service.debit(
					new DebitCommand(transferId, "alice", alice.getId(), bob.getId(), amt("1500"), "ARS")));
		}

		List<Object> results = support.runConcurrently(30, tasks);

		assertNoFailures(results);
		assertThat(results).allMatch(r -> ((OperationResult) r).isSuccess());
		assertThat(results.stream().filter(r -> !((OperationResult) r).replayed()).count()).isEqualTo(1);
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("8500");
		support.assertReconciliationOk();
	}

	@Test
	void theSameCreditCommandDeliveredConcurrentlyCreditsOnce() throws Exception {
		Account alice = support.fundedAccount("alice", "10000");
		Account bob = support.newAccount("bob");
		UUID transferId = UUID.randomUUID();
		service.debit(new DebitCommand(transferId, "alice", alice.getId(), bob.getId(), amt("700"), "ARS"));
		List<Callable<OperationResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> service.credit(new CreditCommand(transferId, bob.getId(), amt("700"), "ARS")));
		}

		List<Object> results = support.runConcurrently(20, tasks);

		assertNoFailures(results);
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("700");
		assertThat(count(ledger.findByAccountIdOrderByCreatedAtAsc(bob.getId()), EntryType.CREDIT)).isEqualTo(1);
		support.assertReconciliationOk();
	}

	@Test
	void concurrentCreditsAndRefundsOnTheSameTransferApplyOnlyOne() throws Exception {
		Account alice = support.fundedAccount("alice", "10000");
		Account bob = support.newAccount("bob");
		UUID transferId = UUID.randomUUID();
		service.debit(new DebitCommand(transferId, "alice", alice.getId(), bob.getId(), amt("700"), "ARS"));
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);
		List<Callable<Object>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> service.credit(new CreditCommand(transferId, bob.getId(), amt("700"), "ARS")));
			tasks.add(() -> service.refund(new RefundCommand(transferId, alice.getId(), amt("700"), "CREDIT_FAILED")));
		}

		List<Object> results = support.runConcurrently(20, tasks);

		// Un refund que llega antes de que exista el CREDIT rechazado falla (precondición): es lo esperado,
		// pero nunca puede haber una excepción distinta ni un doble efecto.
		assertThat(results).noneMatch(r -> r instanceof Throwable && !(r instanceof IllegalStateException));
		service.refund(new RefundCommand(transferId, alice.getId(), amt("700"), "CREDIT_FAILED"));
		assertThat(count(ledger.findByAccountIdOrderByCreatedAtAsc(alice.getId()), EntryType.REVERSAL)).isEqualTo(1);
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("10000");
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("0");
		support.assertReconciliationOk();
	}

	@Test
	void theSameDepositKeySentConcurrentlyCreatesASingleDeposit() throws Exception {
		Account alice = support.newAccount("alice");
		String key = UUID.randomUUID().toString();
		List<Callable<DepositResult>> tasks = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tasks.add(() -> deposits.demoDeposit("alice", key, amt("50000")));
		}

		List<Object> results = support.runConcurrently(20, tasks);

		assertNoFailures(results);
		assertThat(results.stream().filter(r -> ((DepositResult) r).created()).count()).isEqualTo(1);
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
		support.assertReconciliationOk();
	}

}
