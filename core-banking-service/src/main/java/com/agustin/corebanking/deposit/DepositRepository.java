package com.agustin.corebanking.deposit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepositRepository extends JpaRepository<Deposit, UUID> {

	Optional<Deposit> findByRequestedByAndIdempotencyKey(String requestedBy, String idempotencyKey);

}
