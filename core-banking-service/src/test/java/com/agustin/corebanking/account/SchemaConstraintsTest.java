package com.agustin.corebanking.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.agustin.corebanking.support.IntegrationTest;
import com.agustin.corebanking.support.TestSupport;

/** docs/07 §2.2 (Core): la base es la última defensa; estos tests le pegan directo, sin pasar por el código. */
@IntegrationTest
class SchemaConstraintsTest {

	@Autowired
	JdbcClient jdbc;
	@Autowired
	TestSupport support;

	@BeforeEach
	void setUp() {
		support.reset();
	}

	private void insertAccount(UUID id, String userId, String type, String alias, String balance) {
		jdbc.sql("insert into accounts (id, user_id, type, alias, account_number, balance) values (?, ?, ?, ?, 'n', ?::numeric)")
				.params(id, userId, type, alias, balance).update();
	}

	private void insertLedger(UUID accountId, UUID referenceId, String entryType, String amount) {
		jdbc.sql("insert into ledger_entries (id, account_id, reference_type, reference_id, entry_type, amount, balance_after) "
				+ "values (?, ?, 'TRANSFER', ?, ?, ?::numeric, 0)")
				.params(UUID.randomUUID(), accountId, referenceId, entryType, amount).update();
	}

	@Test
	void flywaySeedsTreasuryWithTheFixedIdAndZeroBalance() {
		assertThat(jdbc.sql("select alias || '|' || type || '|' || balance from accounts where id = ?")
				.param(Account.TREASURY_ID).query(String.class).single()).isEqualTo("treasury|SYSTEM|0.0000");
	}

	@Test
	void aUserAccountCanNeverGoNegativeButTreasuryCan() {
		UUID id = UUID.randomUUID();
		insertAccount(id, "u1", "USER", "user.uno", "10");

		assertThatThrownBy(() -> jdbc.sql("update accounts set balance = -0.01 where id = ?").param(id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "u2", "USER", "user.dos", "-1"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbc.sql("update accounts set balance = -500 where id = ?").param(Account.TREASURY_ID).update())
				.isEqualTo(1);
	}

	@Test
	void onlyOneUserAccountPerUserAndAliasesAreUniqueAndWellFormed() {
		insertAccount(UUID.randomUUID(), "u1", "USER", "user.uno", "0");

		assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "u1", "USER", "user.otro", "0"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "u2", "USER", "user.uno", "0"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "u3", "USER", "MAYUS", "0"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void userAccountsRequireAnOwnerAndTypeAndCurrencyAreRestricted() {
		assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), null, "USER", "sin.dueno", "0"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "u1", "OTHER", "tipo.malo", "0"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("update accounts set currency = 'USD' where id = ?")
				.param(Account.TREASURY_ID).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ledgerRejectsTheSameMovementTwiceAndWrongSigns() {
		UUID id = UUID.randomUUID();
		insertAccount(id, "u1", "USER", "user.uno", "0");
		UUID ref = UUID.randomUUID();
		insertLedger(id, ref, "DEBIT", "-5");

		assertThatThrownBy(() -> insertLedger(id, ref, "DEBIT", "-5")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertLedger(id, UUID.randomUUID(), "DEBIT", "5"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertLedger(id, UUID.randomUUID(), "CREDIT", "-5"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertLedger(id, UUID.randomUUID(), "REVERSAL", "-5"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertLedger(id, UUID.randomUUID(), "DEPOSIT", "0"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ledgerEntriesAreImmutable() {
		UUID id = UUID.randomUUID();
		insertAccount(id, "u1", "USER", "user.uno", "0");
		insertLedger(id, UUID.randomUUID(), "CREDIT", "5");

		assertThatThrownBy(() -> jdbc.sql("update ledger_entries set amount = 99 where account_id = ?").param(id).update())
				.hasMessageContaining("immutable");
		assertThatThrownBy(() -> jdbc.sql("delete from ledger_entries where account_id = ?").param(id).update())
				.hasMessageContaining("immutable");
	}

	@Test
	void transferOperationsAreUniquePerTransferAndStep() {
		UUID transfer = UUID.randomUUID();
		String sql = "insert into transfer_operations (id, transfer_id, step, outcome, amount, from_account_id, to_account_id, "
				+ "reply_routing_key, reply_type, reply_payload) values (?, ?, ?, 'SUCCESS', 1, ?, ?, 'rk', 't', '{}'::jsonb)";
		jdbc.sql(sql).params(UUID.randomUUID(), transfer, "DEBIT", UUID.randomUUID(), UUID.randomUUID()).update();
		// otro paso de la misma transferencia: permitido
		jdbc.sql(sql).params(UUID.randomUUID(), transfer, "CREDIT", UUID.randomUUID(), UUID.randomUUID()).update();

		assertThatThrownBy(() -> jdbc.sql(sql)
				.params(UUID.randomUUID(), transfer, "DEBIT", UUID.randomUUID(), UUID.randomUUID()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql(sql)
				.params(UUID.randomUUID(), UUID.randomUUID(), "SETTLE", UUID.randomUUID(), UUID.randomUUID()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void depositsAreUniquePerRequesterAndKeyAndCappedAtOneMillion() {
		UUID id = UUID.randomUUID();
		insertAccount(id, "u1", "USER", "user.uno", "0");
		String sql = "insert into deposits (id, account_id, requested_by, source, idempotency_key, request_hash, amount) "
				+ "values (?, ?, 'u1', 'ADMIN', ?, repeat('a', 64), ?::numeric)";
		jdbc.sql(sql).params(UUID.randomUUID(), id, "k1", "10").update();

		assertThatThrownBy(() -> jdbc.sql(sql).params(UUID.randomUUID(), id, "k1", "10").update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql(sql).params(UUID.randomUUID(), id, "k2", "1000000.01").update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql(sql).params(UUID.randomUUID(), id, "k3", "0").update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
