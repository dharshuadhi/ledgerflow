package com.ledgerflow.reconciliation;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    Page<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
