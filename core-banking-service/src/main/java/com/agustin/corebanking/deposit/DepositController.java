package com.agustin.corebanking.deposit;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.agustin.corebanking.common.Amounts;
import com.agustin.corebanking.deposit.DepositService.DepositResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Fase 1 sin seguridad: {@code X-User-Id} temporal (ver AccountController); el rol ADMIN llega en la Fase 2. */
@RestController
@RequestMapping("/api/accounts")
class DepositController {

	record DepositRequest(@NotNull BigDecimal amount) {
	}

	record DepositResponse(UUID depositId, String balance) {
	}

	private final DepositService service;

	DepositController(DepositService service) {
		this.service = service;
	}

	@PostMapping("/me/demo-deposit")
	ResponseEntity<DepositResponse> demo(@RequestHeader("X-User-Id") String userId,
			@RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody DepositRequest body) {
		return respond(service.demoDeposit(userId, idempotencyKey, body.amount()));
	}

	@PostMapping("/{id}/deposit")
	ResponseEntity<DepositResponse> admin(@RequestHeader(value = "X-User-Id", defaultValue = "admin-dev") String actor,
			@PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody DepositRequest body) {
		return respond(service.adminDeposit(actor, id, idempotencyKey, body.amount()));
	}

	private static ResponseEntity<DepositResponse> respond(DepositResult result) {
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status)
				.body(new DepositResponse(result.depositId(), Amounts.format(result.balance())));
	}

}
