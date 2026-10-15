package com.agustin.corebanking.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.account.AccountService;
import com.agustin.corebanking.account.AccountStatus;
import com.agustin.corebanking.operation.OperationCommands.CreditCommand;
import com.agustin.corebanking.operation.OperationCommands.DebitCommand;
import com.agustin.corebanking.operation.OperationCommands.RefundCommand;
import com.agustin.corebanking.operation.TransferOperationService;
import com.agustin.corebanking.support.IntegrationTest;
import com.agustin.corebanking.support.TestSupport;

/** docs/07 F11 e invariante §0. */
@IntegrationTest
class ReconciliationTest {

	@Autowired
	ReconciliationService reconciliation;
	@Autowired
	TransferOperationService operations;
	@Autowired
	AccountService accountService;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	TestSupport support;

	Account alice;
	Account bob;

	@BeforeEach
	void setUp() {
		support.reset();
		alice = support.fundedAccount("alice", "50000");
		bob = support.newAccount("bob");
	}

	private DebitCommand debit(UUID t, String amount) {
		return new DebitCommand(t, "alice", alice.getId(), bob.getId(), new BigDecimal(amount), "ARS");
	}

	@Test
	void emptySystemIsOk() {
		support.reset();

		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.ok()).isTrue();
		assertThat(r.difference()).isEqualTo("0.00");
		assertThat(r.accountsChecked()).isEqualTo(1); // solo TREASURY
	}

	@Test
	void depositsKeepTheSystemAtZero() {
		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.ok()).isTrue();
		assertThat(r.balanceSum()).isEqualTo("0.00");
		assertThat(r.inFlightAmount()).isEqualTo("0.00");
		assertThat(r.accountsChecked()).isEqualTo(3);
		assertThat(r.mismatches()).isEmpty();
	}

	@Test
	void aDebitedButNotYetCreditedTransferIsInFlightAndStillOk() {
		UUID t = UUID.randomUUID();
		operations.debit(debit(t, "1500"));

		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.ok()).isTrue();
		assertThat(r.inFlightAmount()).isEqualTo("1500.00");
		assertThat(r.inFlightTransfers()).containsExactly(t);
		assertThat(r.balanceSum()).isEqualTo("-1500.00");
		assertThat(r.difference()).isEqualTo("0.00");
	}

	@Test
	void aCompletedTransferIsNoLongerInFlight() {
		UUID t = UUID.randomUUID();
		operations.debit(debit(t, "1500"));
		operations.credit(new CreditCommand(t, bob.getId(), new BigDecimal("1500"), "ARS"));

		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.ok()).isTrue();
		assertThat(r.inFlightAmount()).isEqualTo("0.00");
		assertThat(r.inFlightTransfers()).isEmpty();
	}

	@Test
	void aCompensatedTransferIsNoLongerInFlight() {
		UUID t = UUID.randomUUID();
		operations.debit(debit(t, "1500"));
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);
		operations.credit(new CreditCommand(t, bob.getId(), new BigDecimal("1500"), "ARS"));
		assertThat(reconciliation.reconcile().inFlightTransfers()).containsExactly(t); // rechazado pero sin revertir

		operations.refund(new RefundCommand(t, alice.getId(), new BigDecimal("1500"), "CREDIT_FAILED"));

		ReconciliationResponse r = reconciliation.reconcile();
		assertThat(r.ok()).isTrue();
		assertThat(r.inFlightTransfers()).isEmpty();
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
	}

	@Test
	void aRejectedDebitIsNotInFlight() {
		operations.debit(debit(UUID.randomUUID(), "99999"));

		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.ok()).isTrue();
		assertThat(r.inFlightTransfers()).isEmpty();
	}

	@Test
	void aBalanceTamperedInTheDatabaseIsDetectedAndIdentified() {
		jdbc.sql("update accounts set balance = balance + 5 where id = ?").param(alice.getId()).update();

		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.ok()).isFalse();
		assertThat(r.difference()).isEqualTo("5.00");
		assertThat(r.mismatches()).hasSize(1);
		assertThat(r.mismatches().get(0).accountId()).isEqualTo(alice.getId());
		assertThat(r.mismatches().get(0).balance()).isEqualTo("50005.00");
		assertThat(r.mismatches().get(0).ledgerSum()).isEqualTo("50000.00");
	}

	@Test
	void aMismatchThatCancelsOutInTheTotalIsStillDetectedPerAccount() {
		// el total sigue en 0 (+5 / -5), pero cada cuenta se desvía de su ledger
		jdbc.sql("update accounts set balance = balance + 5 where id = ?").param(alice.getId()).update();
		jdbc.sql("update accounts set balance = balance - 5 where id = ?").param(Account.TREASURY_ID).update();

		ReconciliationResponse r = reconciliation.reconcile();

		assertThat(r.difference()).isEqualTo("0.00");
		assertThat(r.mismatches()).hasSize(2);
		assertThat(r.ok()).isFalse();
	}

}
