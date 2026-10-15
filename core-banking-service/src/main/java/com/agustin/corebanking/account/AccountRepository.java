package com.agustin.corebanking.account;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	Optional<Account> findByUserId(String userId);

	Optional<Account> findByAlias(String alias);

	Page<Account> findByStatus(AccountStatus status, Pageable pageable);

	/** {@code SELECT ... FOR UPDATE}: toda modificación de saldo pasa por acá. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") UUID id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.userId = :userId")
	Optional<Account> findByUserIdForUpdate(@Param("userId") String userId);

}
