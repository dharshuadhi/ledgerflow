package com.ledgerflow.ledger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Page<LedgerTransaction> findByStatusOrderByCreatedAtDesc(TransactionStatus status, Pageable pageable);

    List<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);
}
