package com.agustin.corebanking.reconciliation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationResponse(boolean ok, String balanceSum, String inFlightAmount, String difference,
		int accountsChecked, List<Mismatch> mismatches, List<UUID> inFlightTransfers, Instant executedAt) {

	public record Mismatch(UUID accountId, String balance, String ledgerSum) {
	}

}
