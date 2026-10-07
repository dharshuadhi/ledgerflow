package com.ledgerflow.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Versioned event contracts.
 *
 * <p>Rules every event follows:
 * <ul>
 *   <li>{@code eventId} is globally unique and is the consumer deduplication key.</li>
 *   <li>{@code eventType} carries its version ({@code ledger.transaction.posted.v1}).</li>
 *   <li>New optional fields may be added freely; removing or retyping a field
 *       requires a new version.</li>
 *   <li>Payloads are self-contained: a consumer can act without calling back
 *       into our APIs.</li>
 * </ul>
 */
public final class EventContracts {

    private EventContracts() {
    }

    public record PostingPayload(
            UUID accountId,
            String direction,
            long amountMinorUnits,
            String currency) {
    }

    /** Emitted when a ledger transaction (with all postings) commits. Key: transactionId. */
    public record TransactionPosted(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            UUID transactionId,
            String transactionType,
            String idempotencyKey,
            java.util.Map<String, String> metadata,
            List<PostingPayload> postings) {

        public static final String TYPE = "ledger.transaction.posted.v1";

        public TransactionPosted(UUID transactionId, String transactionType,
                                 String idempotencyKey, java.util.Map<String, String> metadata,
                                 List<PostingPayload> postings) {
            this(UUID.randomUUID(), TYPE, 1, Instant.now(),
                    transactionId, transactionType, idempotencyKey,
                    java.util.Map.copyOf(metadata), List.copyOf(postings));
        }
    }

    /** Emitted after a settlement row is created. Key: settlementId. */
    public record SettlementRequested(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            UUID settlementId,
            UUID transactionId,
            long amountMinorUnits,
            String currency,
            String scenario) {

        public static final String TYPE = "settlement.requested.v1";

        public SettlementRequested(UUID settlementId, UUID transactionId,
                                   long amountMinorUnits, String currency, String scenario) {
            this(UUID.randomUUID(), TYPE, 1, Instant.now(),
                    settlementId, transactionId, amountMinorUnits, currency, scenario);
        }
    }

    /** Emitted when the provider confirms settlement. Key: settlementId. */
    public record SettlementCompleted(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            UUID settlementId,
            UUID transactionId,
            String providerRef,
            Instant settledAt) {

        public static final String TYPE = "settlement.completed.v1";

        public SettlementCompleted(UUID settlementId, UUID transactionId,
                                   String providerRef, Instant settledAt) {
            this(UUID.randomUUID(), TYPE, 1, Instant.now(),
                    settlementId, transactionId, providerRef, settledAt);
        }
    }

    /** Emitted when settlement definitively fails. Key: settlementId. */
    public record SettlementFailed(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            UUID settlementId,
            UUID transactionId,
            String reason) {

        public static final String TYPE = "settlement.failed.v1";

        public SettlementFailed(UUID settlementId, UUID transactionId, String reason) {
            this(UUID.randomUUID(), TYPE, 1, Instant.now(),
                    settlementId, transactionId, reason);
        }
    }

    /** Emitted when reconciliation finds a mismatch. Key: runId. */
    public record ReconciliationMismatchDetected(
            UUID eventId,
            String eventType,
            int eventVersion,
            Instant occurredAt,
            UUID runId,
            UUID mismatchId,
            String mismatchType,
            UUID transactionId,
            UUID settlementId) {

        public static final String TYPE = "reconciliation.mismatch.detected.v1";

        public ReconciliationMismatchDetected(UUID runId, UUID mismatchId, String mismatchType,
                                              UUID transactionId, UUID settlementId) {
            this(UUID.randomUUID(), TYPE, 1, Instant.now(),
                    runId, mismatchId, mismatchType, transactionId, settlementId);
        }
    }
}
