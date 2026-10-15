package com.agustin.corebanking.operation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.account.AccountService;
import com.agustin.corebanking.account.AccountStatus;
import com.agustin.corebanking.ledger.EntryType;
import com.agustin.corebanking.ledger.LedgerEntry;
import com.agustin.corebanking.ledger.LedgerRepository;
import com.agustin.corebanking.operation.OperationCommands.CreditCommand;
import com.agustin.corebanking.operation.OperationCommands.DebitCommand;
import com.agustin.corebanking.operation.OperationCommands.RefundCommand;
import com.agustin.corebanking.support.IntegrationTest;
import com.agustin.corebanking.support.TestSupport;

@IntegrationTest
class TransferOperationServiceTest {

	@Autowired
	TransferOperationService service;
	@Autowired
	AccountService accountService;
	@Autowired
	LedgerRepository ledger;
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

	private DebitCommand debitCmd(UUID transferId, String amount) {
		return new DebitCommand(transferId, "alice", alice.getId(), bob.getId(), new BigDecimal(amount), "ARS");
	}

	private CreditCommand creditCmd(UUID transferId, String amount) {
		return new CreditCommand(transferId, bob.getId(), new BigDecimal(amount), "ARS");
	}

	private RefundCommand refundCmd(UUID transferId, String amount) {
		return new RefundCommand(transferId, alice.getId(), new BigDecimal(amount), "CREDIT_FAILED");
	}

	private List<LedgerEntry> entries(UUID accountId, EntryType type) {
		return ledger.findByAccountIdOrderByCreatedAtAsc(accountId).stream().filter(e -> e.getEntryType() == type)
				.toList();
	}

	@Test
	void debitMovesMoneyWritesNegativeEntryAndReturnsFundsDebited() {
		UUID t = UUID.randomUUID();

		OperationResult r = service.debit(debitCmd(t, "1500"));

		assertThat(r.isSuccess()).isTrue();
		assertThat(r.replayed()).isFalse();
		assertThat(r.replyType()).isEqualTo("FundsDebited");
		assertThat(r.replyRoutingKey()).isEqualTo("tx.reply.core.debited");
		assertThat(r.payload()).containsEntry("amount", "1500.00").containsEntry("balanceAfter", "48500.00");
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("48500");
		List<LedgerEntry> debits = entries(alice.getId(), EntryType.DEBIT);
		assertThat(debits).hasSize(1);
		assertThat(debits.get(0).getAmount()).isEqualByComparingTo("-1500");
		assertThat(debits.get(0).getBalanceAfter()).isEqualByComparingTo("48500");
		assertThat(debits.get(0).getCounterpartyAlias()).isEqualTo(bob.getAlias());
		support.assertReconciliationOk();
	}

	@Test
	void creditAfterDebitCompletesTheTransfer() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "1500"));

		OperationResult r = service.credit(creditCmd(t, "1500"));

		assertThat(r.isSuccess()).isTrue();
		assertThat(r.replyType()).isEqualTo("FundsCredited");
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("1500");
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("48500");
		assertThat(entries(bob.getId(), EntryType.CREDIT)).hasSize(1);
		support.assertReconciliationOk();
	}

	@Test
	void insufficientFundsIsRejectedWithoutMovingMoney() {
		OperationResult r = service.debit(debitCmd(UUID.randomUUID(), "50000.01"));

		assertThat(r.isSuccess()).isFalse();
		assertThat(r.replyType()).isEqualTo("DebitRejected");
		assertThat(r.reason()).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
		assertThat(entries(alice.getId(), EntryType.DEBIT)).isEmpty();
		support.assertReconciliationOk();
	}

	@Test
	void exactBalanceCanBeDebitedLeavingZero() {
		OperationResult r = service.debit(debitCmd(UUID.randomUUID(), "50000"));

		assertThat(r.isSuccess()).isTrue();
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("0");
	}

	@Test
	void debitingAnotherUsersAccountIsRejectedAsNotOwner() {
		DebitCommand cmd = new DebitCommand(UUID.randomUUID(), "mallory", alice.getId(), bob.getId(),
				new BigDecimal("10"), "ARS");

		assertThat(service.debit(cmd).reason()).isEqualTo("NOT_OWNER");
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
	}

	@Test
	void blockedSourceIsRejected() {
		accountService.changeStatus(alice.getId(), AccountStatus.BLOCKED);

		assertThat(service.debit(debitCmd(UUID.randomUUID(), "10")).reason()).isEqualTo("ACCOUNT_BLOCKED");
	}

	@Test
	void blockedOrMissingDestinationIsRejectedAtDebit() {
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);
		assertThat(service.debit(debitCmd(UUID.randomUUID(), "10")).reason()).isEqualTo("DESTINATION_UNAVAILABLE");

		DebitCommand toNobody = new DebitCommand(UUID.randomUUID(), "alice", alice.getId(), UUID.randomUUID(),
				new BigDecimal("10"), "ARS");
		assertThat(service.debit(toNobody).reason()).isEqualTo("DESTINATION_UNAVAILABLE");
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
	}

	@Test
	void currencyMismatchAndMissingSourceAreRejected() {
		DebitCommand usd = new DebitCommand(UUID.randomUUID(), "alice", alice.getId(), bob.getId(),
				new BigDecimal("10"), "USD");
		assertThat(service.debit(usd).reason()).isEqualTo("CURRENCY_MISMATCH");

		DebitCommand ghost = new DebitCommand(UUID.randomUUID(), "alice", UUID.randomUUID(), bob.getId(),
				new BigDecimal("10"), "ARS");
		assertThat(service.debit(ghost).reason()).isEqualTo("ACCOUNT_NOT_FOUND");
	}

	@Test
	void duplicateDebitReEmitsTheStoredReplyAndDoesNotDebitTwice() {
		UUID t = UUID.randomUUID();
		OperationResult first = service.debit(debitCmd(t, "1500"));

		OperationResult second = service.debit(debitCmd(t, "1500"));

		assertThat(second.replayed()).isTrue();
		assertThat(second.payload()).isEqualTo(first.payload());
		assertThat(second.replyType()).isEqualTo(first.replyType());
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("48500");
		assertThat(entries(alice.getId(), EntryType.DEBIT)).hasSize(1);
		support.assertReconciliationOk();
	}

	@Test
	void duplicateOfARejectedDebitKeepsReturningTheRejection() {
		UUID t = UUID.randomUUID();
		assertThat(service.debit(debitCmd(t, "99999")).reason()).isEqualTo("INSUFFICIENT_FUNDS");
		support.fundedAccount("carol", "1"); // el mundo cambia, la respuesta guardada no

		OperationResult again = service.debit(debitCmd(t, "99999"));

		assertThat(again.replayed()).isTrue();
		assertThat(again.reason()).isEqualTo("INSUFFICIENT_FUNDS");
	}

	@Test
	void duplicateCreditIsReplayedAndCreditsOnce() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "1500"));
		service.credit(creditCmd(t, "1500"));

		OperationResult again = service.credit(creditCmd(t, "1500"));

		assertThat(again.replayed()).isTrue();
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("1500");
		assertThat(entries(bob.getId(), EntryType.CREDIT)).hasSize(1);
	}

	@Test
	void creditRejectedByBlockedDestinationThenRefundRestoresTheSource() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "1500"));
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);

		OperationResult credit = service.credit(creditCmd(t, "1500"));
		assertThat(credit.isSuccess()).isFalse();
		assertThat(credit.replyType()).isEqualTo("CreditFailed");
		assertThat(credit.reason()).isEqualTo("DESTINATION_BLOCKED");
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("0");
		support.assertReconciliationOk(); // todavía en vuelo: el débito sigue sin acreditar ni revertir

		OperationResult refund = service.refund(refundCmd(t, "1500"));

		assertThat(refund.isSuccess()).isTrue();
		assertThat(refund.replyType()).isEqualTo("FundsRefunded");
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
		List<LedgerEntry> reversals = entries(alice.getId(), EntryType.REVERSAL);
		assertThat(reversals).hasSize(1);
		assertThat(reversals.get(0).getAmount()).isEqualByComparingTo("1500");
		support.assertReconciliationOk();
	}

	@Test
	void refundIgnoresABlockedSourceAccount() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "1500"));
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);
		service.credit(creditCmd(t, "1500"));
		accountService.changeStatus(alice.getId(), AccountStatus.BLOCKED);

		OperationResult refund = service.refund(refundCmd(t, "1500"));

		assertThat(refund.isSuccess()).isTrue();
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
		support.assertReconciliationOk();
	}

	@Test
	void duplicateRefundIsReplayedAndRefundsOnce() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "1500"));
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);
		service.credit(creditCmd(t, "1500"));
		service.refund(refundCmd(t, "1500"));

		OperationResult again = service.refund(refundCmd(t, "1500"));

		assertThat(again.replayed()).isTrue();
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("50000");
		assertThat(entries(alice.getId(), EntryType.REVERSAL)).hasSize(1);
	}

	@Test
	void creditAndRefundAreMutuallyExclusive() {
		UUID ok = UUID.randomUUID();
		service.debit(debitCmd(ok, "100"));
		service.credit(creditCmd(ok, "100"));
		// crédito exitoso => no se puede revertir
		assertThatThrownBy(() -> service.refund(refundCmd(ok, "100"))).isInstanceOf(IllegalStateException.class);

		UUID failed = UUID.randomUUID();
		service.debit(debitCmd(failed, "100"));
		accountService.changeStatus(bob.getId(), AccountStatus.BLOCKED);
		service.credit(creditCmd(failed, "100"));
		service.refund(refundCmd(failed, "100"));
		// refund hecho => el crédito pedido otra vez devuelve el rechazo guardado, nunca acredita
		OperationResult retry = service.credit(creditCmd(failed, "100"));
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.isSuccess()).isFalse();
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("100"); // solo el de la transferencia `ok`
		support.assertReconciliationOk();
	}

	@Test
	void creditOrRefundWithoutASuccessfulDebitIsAnError() {
		UUID never = UUID.randomUUID();
		assertThatThrownBy(() -> service.credit(creditCmd(never, "10"))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service.refund(refundCmd(never, "10"))).isInstanceOf(IllegalStateException.class);

		UUID rejected = UUID.randomUUID();
		service.debit(debitCmd(rejected, "99999"));
		assertThatThrownBy(() -> service.credit(creditCmd(rejected, "99999"))).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service.refund(refundCmd(rejected, "99999"))).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refundWithoutAnyCreditAttemptIsAnError() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "100"));

		assertThatThrownBy(() -> service.refund(refundCmd(t, "100"))).isInstanceOf(IllegalStateException.class);
		assertThat(support.balance(alice.getId())).isEqualByComparingTo("49900");
	}

	@Test
	void mismatchedAmountOrAccountsAreRejectedAsProgrammingErrors() {
		UUID t = UUID.randomUUID();
		service.debit(debitCmd(t, "100"));

		assertThatThrownBy(() -> service.credit(creditCmd(t, "999"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.credit(new CreditCommand(t, UUID.randomUUID(), new BigDecimal("100"), "ARS")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(support.balance(bob.getId())).isEqualByComparingTo("0");
	}

	@Test
	void nonPositiveAmountsAreRefused() {
		assertThatThrownBy(() -> service.debit(debitCmd(UUID.randomUUID(), "0")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.debit(debitCmd(UUID.randomUUID(), "-5")))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
