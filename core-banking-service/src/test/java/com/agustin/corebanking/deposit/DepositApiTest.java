package com.agustin.corebanking.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.agustin.corebanking.account.Account;
import com.agustin.corebanking.ledger.EntryType;
import com.agustin.corebanking.ledger.LedgerEntry;
import com.agustin.corebanking.ledger.LedgerRepository;
import com.agustin.corebanking.support.IntegrationTest;
import com.agustin.corebanking.support.TestSupport;

/** docs/07 F4: cargar saldo demo y depósitos de ADMIN. */
@IntegrationTest
class DepositApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	LedgerRepository ledger;
	@Autowired
	TestSupport support;

	Account user1;

	@BeforeEach
	void setUp() {
		support.reset();
		user1 = support.newAccount("user1");
	}

	private ResultActions demo(String key, String amount) throws Exception {
		return mvc.perform(post("/api/accounts/me/demo-deposit").header("X-User-Id", "user1")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":\"" + amount + "\"}"));
	}

	private ResultActions admin(UUID accountId, String key, String amount) throws Exception {
		return mvc.perform(post("/api/accounts/" + accountId + "/deposit").header("X-User-Id", "admin1")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\":\"" + amount + "\"}"));
	}

	@Test
	void demoDepositCreditsTheAccountWithTwoBalancedEntries() throws Exception {
		demo("k1", "50000.00").andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value("50000.00"))
				.andExpect(jsonPath("$.depositId").exists());

		assertThat(support.balance(user1.getId())).isEqualByComparingTo("50000");
		List<LedgerEntry> mine = ledger.findByAccountIdOrderByCreatedAtAsc(user1.getId());
		List<LedgerEntry> treasury = ledger.findByAccountIdOrderByCreatedAtAsc(Account.TREASURY_ID);
		assertThat(mine).hasSize(1);
		assertThat(mine.get(0).getEntryType()).isEqualTo(EntryType.DEPOSIT);
		assertThat(mine.get(0).getAmount()).isEqualByComparingTo("50000");
		assertThat(treasury).hasSize(1);
		assertThat(treasury.get(0).getEntryType()).isEqualTo(EntryType.DEPOSIT);
		assertThat(treasury.get(0).getAmount()).isEqualByComparingTo("-50000");
		assertThat(treasury.get(0).getReferenceId()).isEqualTo(mine.get(0).getReferenceId());
		// TREASURY queda en negativo y la conciliación sigue en ok
		assertThat(support.balance(Account.TREASURY_ID)).isEqualByComparingTo("-50000");
		support.assertReconciliationOk();
	}

	@Test
	void repeatingTheSameRequestWithTheSameKeyDepositsOnlyOnce() throws Exception {
		String first = demo("k1", "50000.00").andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();

		demo("k1", "50000.00").andExpect(status().isOk()).andExpect(jsonPath("$.balance").value("50000.00"))
				.andExpect(jsonPath("$.depositId").value(org.hamcrest.Matchers
						.is(first.replaceAll(".*\"depositId\":\"([^\"]+)\".*", "$1"))));

		assertThat(support.balance(user1.getId())).isEqualByComparingTo("50000");
		assertThat(ledger.findByAccountIdOrderByCreatedAtAsc(user1.getId())).hasSize(1);
		support.assertReconciliationOk();
	}

	@Test
	void sameKeyWithAnotherAmountIsRejected() throws Exception {
		demo("k1", "50000.00").andExpect(status().isCreated());

		demo("k1", "100.00").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(support.balance(user1.getId())).isEqualByComparingTo("50000");
	}

	@Test
	void amountsWithTheSameValueButDifferentScaleAreTheSameRequest() throws Exception {
		demo("k1", "50000").andExpect(status().isCreated());

		demo("k1", "50000.00").andExpect(status().isOk());
		assertThat(support.balance(user1.getId())).isEqualByComparingTo("50000");
	}

	@Test
	void demoLimitIsStrictlyFiftyThousand() throws Exception {
		demo("k1", "50000.01").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("DEMO_LIMIT_EXCEEDED"));
		demo("k2", "50000.00").andExpect(status().isCreated());
	}

	@Test
	void balanceCapOfTwoHundredThousandBlocksDemoDeposits() throws Exception {
		admin(user1.getId(), "a1", "180000.00").andExpect(status().isCreated());

		demo("k1", "50000.00").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("BALANCE_CAP_EXCEEDED"));
		demo("k2", "20000.00").andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value("200000.00"));
		demo("k3", "0.01").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("BALANCE_CAP_EXCEEDED"));
		assertThat(support.balance(user1.getId())).isEqualByComparingTo("200000");
	}

	@Test
	void aRetryStillWorksAfterTheCapWasReached() throws Exception {
		admin(user1.getId(), "a1", "150000.00").andExpect(status().isCreated());
		demo("k1", "50000.00").andExpect(status().isCreated()); // saldo = 200.000, justo en el tope

		demo("k1", "50000.00").andExpect(status().isOk()); // reintento: no falla por el tope
	}

	@Test
	void invalidAmountsAreRejected() throws Exception {
		demo("k1", "0").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
		demo("k2", "-10").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
		demo("k3", "10.123").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
		mvc.perform(post("/api/accounts/me/demo-deposit").header("X-User-Id", "user1").header("Idempotency-Key", "k4")
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(post("/api/accounts/me/demo-deposit").header("X-User-Id", "user1").header("Idempotency-Key", "k5")
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"abc\"}"))
				.andExpect(status().isBadRequest());
		assertThat(support.balance(user1.getId())).isEqualByComparingTo("0");
	}

	@Test
	void theIdempotencyKeyIsRequired() throws Exception {
		mvc.perform(post("/api/accounts/me/demo-deposit").header("X-User-Id", "user1")
				.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"10\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void adminDepositUpToOneMillion() throws Exception {
		admin(user1.getId(), "a1", "400000.00").andExpect(status().isCreated())
				.andExpect(jsonPath("$.balance").value("400000.00"));
		admin(user1.getId(), "a2", "1000000.00").andExpect(status().isCreated());
		admin(user1.getId(), "a3", "1000000.01").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("AMOUNT_LIMIT_EXCEEDED"));
		support.assertReconciliationOk();
	}

	@Test
	void adminDepositToUnknownOrSystemOrBlockedAccountIsRefused() throws Exception {
		admin(UUID.randomUUID(), "a1", "10").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
		admin(Account.TREASURY_ID, "a2", "10").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/api/accounts/" + user1.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"BLOCKED\"}")).andExpect(status().isOk());
		admin(user1.getId(), "a3", "10").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
		demo("k1", "10").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
	}

	@Test
	void reconciliationEndpointReportsOkAfterDeposits() throws Exception {
		demo("k1", "50000.00").andExpect(status().isCreated());
		admin(user1.getId(), "a1", "1000.50").andExpect(status().isCreated());

		mvc.perform(get("/api/admin/reconciliation")).andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.difference").value("0.00")).andExpect(jsonPath("$.balanceSum").value("0.00"))
				.andExpect(jsonPath("$.mismatches").isEmpty());
	}

}
