package com.agustin.corebanking.account;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.agustin.corebanking.account.AccountDtos.AccountResponse;
import com.agustin.corebanking.account.AccountDtos.AdminAccountResponse;
import com.agustin.corebanking.account.AccountDtos.AliasRequest;
import com.agustin.corebanking.account.AccountDtos.LookupResponse;
import com.agustin.corebanking.account.AccountDtos.MovementResponse;
import com.agustin.corebanking.account.AccountDtos.StatusRequest;
import com.agustin.corebanking.common.PageResponse;

import jakarta.validation.Valid;

/**
 * Fase 1 sin seguridad: el usuario llega en la cabecera temporal {@code X-User-Id}. La Fase 2
 * la reemplaza por el {@code sub} del JWT (y agrega los roles USER/ADMIN).
 */
@RestController
@RequestMapping("/api")
class AccountController {

	static final String USER_HEADER = "X-User-Id";

	private final AccountService service;

	AccountController(AccountService service) {
		this.service = service;
	}

	@PostMapping("/accounts")
	ResponseEntity<AccountResponse> create(@RequestHeader(USER_HEADER) String userId) {
		AccountService.CreateResult result = service.createAccount(userId);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(AccountResponse.forOwner(result.account()));
	}

	@GetMapping("/accounts/me")
	AccountResponse me(@RequestHeader(USER_HEADER) String userId) {
		return AccountResponse.forOwner(service.getByUserId(userId));
	}

	@PatchMapping("/accounts/me/alias")
	AccountResponse changeAlias(@RequestHeader(USER_HEADER) String userId, @Valid @RequestBody AliasRequest body) {
		return AccountResponse.forOwner(service.changeAlias(userId, body.alias()));
	}

	@GetMapping("/accounts/lookup")
	LookupResponse lookup(@RequestParam String alias) {
		return LookupResponse.from(service.lookup(alias));
	}

	@GetMapping("/accounts/{id}/movements")
	PageResponse<MovementResponse> movements(@RequestHeader(USER_HEADER) String userId, @PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return PageResponse.of(service.movements(userId, id, PageResponse.pageable(page, size)),
				MovementResponse::from);
	}

	@PatchMapping("/accounts/{id}/status")
	AccountResponse changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest body) {
		return AccountResponse.masked(service.changeStatus(id, body.status()));
	}

	@GetMapping("/admin/accounts")
	PageResponse<AdminAccountResponse> adminList(@RequestParam(required = false) AccountStatus status,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return PageResponse.of(service.listForAdmin(status, PageResponse.pageable(page, size)),
				AdminAccountResponse::from);
	}

}
