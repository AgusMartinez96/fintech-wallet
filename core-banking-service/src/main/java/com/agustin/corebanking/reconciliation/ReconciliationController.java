package com.agustin.corebanking.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fase 1 sin seguridad; en la Fase 2 queda solo para ADMIN. */
@RestController
class ReconciliationController {

	private final ReconciliationService service;

	ReconciliationController(ReconciliationService service) {
		this.service = service;
	}

	@GetMapping("/api/admin/reconciliation")
	ReconciliationResponse reconcile() {
		return service.reconcile();
	}

}
