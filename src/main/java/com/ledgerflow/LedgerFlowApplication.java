package com.ledgerflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * LedgerFlow — auditable event-driven payment ledger.
 *
 * <p>A modular monolith: one deployable service with strict package boundaries.
 * Money movement is recorded as double-entry postings in PostgreSQL (the
 * authoritative store); state changes are published to Kafka via a transactional
 * outbox; read models (balances) are derived projections that can be rebuilt.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerFlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerFlowApplication.class, args);
    }
}
