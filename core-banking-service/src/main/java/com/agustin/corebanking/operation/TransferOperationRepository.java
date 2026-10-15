package com.agustin.corebanking.operation;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface TransferOperationRepository extends JpaRepository<TransferOperation, UUID> {

	Optional<TransferOperation> findByTransferIdAndStep(UUID transferId, OperationStep step);

	/** Bloquea la fila del paso: serializa crédito y refund de la misma transferencia. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select o from TransferOperation o where o.transferId = :transferId and o.step = :step")
	Optional<TransferOperation> findForUpdate(@Param("transferId") UUID transferId, @Param("step") OperationStep step);

}
