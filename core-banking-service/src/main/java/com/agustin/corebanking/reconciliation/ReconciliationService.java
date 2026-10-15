package com.agustin.corebanking.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.agustin.corebanking.common.Amounts;
import com.agustin.corebanking.reconciliation.ReconciliationResponse.Mismatch;

/**
 * Invariante (docs/02 §5): suma(saldos de todas las cuentas, incluida TREASURY) + montos debitados
 * todavía no acreditados ni revertidos = 0; y por cuenta, balance = suma(ledger_entries.amount).
 * Todo en una transacción REPEATABLE READ de solo lectura para ver una foto consistente.
 */
@Service
public class ReconciliationService {

	private final JdbcClient jdbc;

	public ReconciliationService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
	public ReconciliationResponse reconcile() {
		BigDecimal balanceSum = jdbc.sql("select coalesce(sum(balance), 0) from accounts")
				.query(BigDecimal.class).single();
		int accountsChecked = jdbc.sql("select count(*) from accounts").query(Integer.class).single();

		List<InFlight> inFlight = jdbc.sql("""
				select o.transfer_id, o.amount
				from transfer_operations o
				where o.step = 'DEBIT' and o.outcome = 'SUCCESS'
				  and not exists (
				      select 1 from transfer_operations x
				      where x.transfer_id = o.transfer_id
				        and x.step in ('CREDIT', 'REFUND') and x.outcome = 'SUCCESS')
				""").query((rs, i) -> new InFlight(rs.getObject("transfer_id", UUID.class), rs.getBigDecimal("amount")))
				.list();
		BigDecimal inFlightAmount = inFlight.stream().map(InFlight::amount).reduce(BigDecimal.ZERO, BigDecimal::add);

		List<Mismatch> mismatches = jdbc.sql("""
				select a.id, a.balance, coalesce(sum(l.amount), 0) as ledger_sum
				from accounts a left join ledger_entries l on l.account_id = a.id
				group by a.id, a.balance
				having a.balance <> coalesce(sum(l.amount), 0)
				""").query((rs, i) -> new Mismatch(rs.getObject("id", UUID.class),
						Amounts.format(rs.getBigDecimal("balance")), Amounts.format(rs.getBigDecimal("ledger_sum"))))
				.list();

		BigDecimal difference = balanceSum.add(inFlightAmount);
		boolean ok = difference.signum() == 0 && mismatches.isEmpty();
		return new ReconciliationResponse(ok, Amounts.format(balanceSum), Amounts.format(inFlightAmount),
				Amounts.format(difference), accountsChecked, mismatches,
				inFlight.stream().map(InFlight::transferId).toList(), Instant.now());
	}

	private record InFlight(UUID transferId, BigDecimal amount) {
	}

}
