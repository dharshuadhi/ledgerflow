package com.ledgerflow.settlement;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    Optional<Settlement> findByTransactionId(UUID transactionId);

    Optional<Settlement> findByProviderEventId(String providerEventId);

    List<Settlement> findByStatus(SettlementStatus status);

    /** Pessimistic lock: serializes concurrent duplicate webhook deliveries. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id = :id")
    Optional<Settlement> lockById(@Param("id") UUID id);
}
