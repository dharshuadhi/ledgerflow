package com.ledgerflow.reconciliation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReconciliationMismatchRepository extends JpaRepository<ReconciliationMismatch, UUID> {

    Page<ReconciliationMismatch> findByResolvedAtIsNullOrderByCreatedAtDesc(Pageable pageable);

    /** Open mismatches for one settlement+type, to avoid re-reporting every run. */
    @Query("select m from ReconciliationMismatch m where m.resolvedAt is null"
            + " and m.mismatchType = :type"
            + " and ((:settlementId is null and m.settlement is null) or (m.settlement.id = :settlementId))"
            + " and ((:transactionId is null and m.transaction is null) or (m.transaction.id = :transactionId))")
    List<ReconciliationMismatch> findOpen(MismatchType type, UUID settlementId, UUID transactionId);

    long countByResolvedAtIsNull();
}
