package com.agustin.corebanking.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.agustin.corebanking.support.IntegrationTest;
import com.agustin.corebanking.support.TestSupport;

/** docs/07 F2, F3 y F7: cuenta automática, alias, lookup, movimientos y propiedad. */
@IntegrationTest
class AccountApiTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JdbcClient jdbc;
	@Autowired
	TestSupport support;
	@Autowired
	AccountRepository accounts;

	@BeforeEach
	void setUp() {
		support.reset();
	}

	private ResultActions createAccount(String userId) throws Exception {
		return mvc.perform(post("/api/accounts").header("X-User-Id", userId));
	}

	private ResultActions changeAlias(String userId, String alias) throws Exception {
		return mvc.perform(patch("/api/accounts/me/alias").header("X-User-Id", userId)
				.contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"" + alias + "\"}"));
	}

	@Test
	void meIs404UntilTheAccountIsCreatedThenItIsCreatedOnce() throws Exception {
		mvc.perform(get("/api/accounts/me").header("X-User-Id", "user1")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.path").value("/api/accounts/me"));

		createAccount("user1").andExpect(status().isCreated()).andExpect(jsonPath("$.balance").value("0.00"))
				.andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.currency").value("ARS"))
				.andExpect(jsonPath("$.alias").value(startsWith("wallet.")))
				.andExpect(jsonPath("$.aliasEditable").value(true))
				.andExpect(jsonPath("$.accountNumber").value(matchesPattern("\\d{22}")));

		mvc.perform(get("/api/accounts/me").header("X-User-Id", "user1")).andExpect(status().isOk());
	}

	@Test
	void creatingTwiceReturnsTheSameAccount() throws Exception {
		String first = createAccount("user1").andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
		String id = first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

		createAccount("user1").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
	}

	@Test
	void parallelCreationsLeaveASingleRowForTheUser() throws Exception {
		List<Callable<UUID>> tasks = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			tasks.add(() -> UUID.fromString(support.newAccount("user1").getId().toString()));
		}

		List<Object> results = support.runConcurrently(12, tasks);

		assertThat(results).noneMatch(r -> r instanceof Throwable);
		assertThat(results.stream().distinct().count()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from accounts where user_id = 'user1'").query(Integer.class).single())
				.isEqualTo(1);
	}

	@Test
	void accountNumberIsEncryptedAtRestButTheOwnerSeesItComplete() throws Exception {
		String body = createAccount("user1").andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
		String number = body.replaceAll(".*\"accountNumber\":\"([^\"]+)\".*", "$1");

		String stored = jdbc.sql("select account_number from accounts where user_id = 'user1'").query(String.class)
				.single();

		assertThat(number).matches("\\d{22}");
		assertThat(stored).startsWith("v1:").doesNotContain(number).doesNotMatch("\\d{22}");
	}

	@Test
	void otherUsersSeeTheAccountNumberMasked() throws Exception {
		createAccount("user1").andExpect(status().isCreated());
		UUID id = accounts.findByUserId("user1").orElseThrow().getId();

		mvc.perform(patch("/api/accounts/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ACTIVE\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.accountNumber").value(matchesPattern("\\*{4}\\d{4}")));
		mvc.perform(get("/api/admin/accounts")).andExpect(status().isOk())
				.andExpect(jsonPath("$.content[?(@.userId=='user1')].accountNumber").value(
						org.hamcrest.Matchers.contains(matchesPattern("\\*{4}\\d{4}"))));
	}

	@Test
	void aliasCanBeChangedOnlyOnce() throws Exception {
		createAccount("user1");

		changeAlias("user1", "mi.alias").andExpect(status().isOk()).andExpect(jsonPath("$.alias").value("mi.alias"))
				.andExpect(jsonPath("$.aliasEditable").value(false));
		changeAlias("user1", "otro.alias").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALIAS_ALREADY_CHANGED"));
	}

	@Test
	void aliasIsUniqueAndValidated() throws Exception {
		createAccount("user1");
		createAccount("user2");
		changeAlias("user1", "mi.alias").andExpect(status().isOk());

		changeAlias("user2", "mi.alias").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALIAS_TAKEN"));
		changeAlias("user2", "MI.ALIAS").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("ALIAS_TAKEN")); // se normaliza a minúsculas
		changeAlias("user2", "treasury").andExpect(status().isConflict());
		changeAlias("user2", "no valido!").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("ALIAS_INVALID"));
		changeAlias("user2", "ab").andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("ALIAS_INVALID"));
		mvc.perform(patch("/api/accounts/me/alias").header("X-User-Id", "user2").contentType(MediaType.APPLICATION_JSON)
				.content("{\"alias\":\"\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.fieldErrors[0].field").value("alias"));
		// un intento fallido no consume el único cambio permitido
		changeAlias("user2", "libre.ahora").andExpect(status().isOk());
	}

	@Test
	void lookupExposesOnlyIdAndAlias() throws Exception {
		createAccount("user1");
		changeAlias("user1", "destino.uno");

		mvc.perform(get("/api/accounts/lookup").param("alias", "destino.uno")).andExpect(status().isOk())
				.andExpect(jsonPath("$.alias").value("destino.uno")).andExpect(jsonPath("$.id").exists())
				.andExpect(jsonPath("$.balance").doesNotExist()).andExpect(jsonPath("$.accountNumber").doesNotExist())
				.andExpect(jsonPath("$.userId").doesNotExist());
		mvc.perform(get("/api/accounts/lookup").param("alias", "Destino.Uno")).andExpect(status().isOk());
		mvc.perform(get("/api/accounts/lookup").param("alias", "no.existe")).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	@Test
	void lookupHidesBlockedAccountsAndTheSystemAccount() throws Exception {
		createAccount("user1");
		changeAlias("user1", "bloqueada");
		UUID id = accounts.findByUserId("user1").orElseThrow().getId();
		mvc.perform(patch("/api/accounts/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"BLOCKED\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("BLOCKED"));

		mvc.perform(get("/api/accounts/lookup").param("alias", "bloqueada")).andExpect(status().isNotFound());
		mvc.perform(get("/api/accounts/lookup").param("alias", "treasury")).andExpect(status().isNotFound());
	}

	@Test
	void treasuryCannotBeBlocked() throws Exception {
		mvc.perform(patch("/api/accounts/" + Account.TREASURY_ID + "/status").contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"BLOCKED\"}")).andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("SYSTEM_ACCOUNT_IMMUTABLE"));
	}

	@Test
	void movementsAreOwnedPagedAndNewestFirst() throws Exception {
		Account a = support.fundedAccount("user1", "1000");
		for (int i = 1; i <= 3; i++) {
			mvc.perform(post("/api/accounts/me/demo-deposit").header("X-User-Id", "user1")
					.header("Idempotency-Key", "d" + i).contentType(MediaType.APPLICATION_JSON)
					.content("{\"amount\":\"" + (i * 10) + "\"}")).andExpect(status().isCreated());
			Thread.sleep(5);
		}

		String url = "/api/accounts/" + a.getId() + "/movements";
		mvc.perform(get(url).header("X-User-Id", "user1").param("page", "0").param("size", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content[0].entryType").value("DEPOSIT"))
				.andExpect(jsonPath("$.content[0].amount").value("30.00"))
				.andExpect(jsonPath("$.content[0].balanceAfter").value("1060.00"))
				.andExpect(jsonPath("$.content[0].counterpartyAlias").value("treasury"))
				.andExpect(jsonPath("$.content[0].referenceType").value("DEPOSIT"))
				.andExpect(jsonPath("$.content[1].amount").value("20.00"));
		mvc.perform(get(url).header("X-User-Id", "user1").param("page", "1").param("size", "2"))
				.andExpect(jsonPath("$.content[0].amount").value("10.00"))
				.andExpect(jsonPath("$.content[1].amount").value("1000.00"));
	}

	@Test
	void anotherUsersMovementsAreNotFoundNotForbidden() throws Exception {
		Account a = support.fundedAccount("user1", "1000");
		support.newAccount("user2");

		mvc.perform(get("/api/accounts/" + a.getId() + "/movements").header("X-User-Id", "user2"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
		mvc.perform(get("/api/accounts/" + UUID.randomUUID() + "/movements").header("X-User-Id", "user1"))
				.andExpect(status().isNotFound());
	}

	@Test
	void accountWithoutMovementsReturnsAnEmptyPage() throws Exception {
		Account a = support.newAccount("user1");

		mvc.perform(get("/api/accounts/" + a.getId() + "/movements").header("X-User-Id", "user1"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void balanceIsSerializedAsADecimalString() throws Exception {
		support.fundedAccount("user1", "1500");

		mvc.perform(get("/api/accounts/me").header("X-User-Id", "user1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value("1500.00"));
	}

	@Test
	void missingUserHeaderAndMalformedIdsAreBadRequests() throws Exception {
		mvc.perform(get("/api/accounts/me")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		mvc.perform(get("/api/accounts/not-a-uuid/movements").header("X-User-Id", "user1"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
	}

	@Test
	void adminListIsPagedAndFilterableByStatus() throws Exception {
		support.newAccount("user1");
		support.newAccount("user2");

		mvc.perform(get("/api/admin/accounts").param("size", "1")).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1)).andExpect(jsonPath("$.totalElements").value(3));
		mvc.perform(get("/api/admin/accounts").param("status", "BLOCKED")).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(0));
	}
}
