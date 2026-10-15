package com.agustin.corebanking.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerRepository extends JpaRepository<LedgerEntry, UUID> {

	Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

	List<LedgerEntry> findByAccountIdOrderByCreatedAtAsc(UUID accountId);

	@Query("select coalesce(sum(l.amount), 0) from LedgerEntry l where l.accountId = :accountId")
	BigDecimal sumByAccountId(@Param("accountId") UUID accountId);

}
