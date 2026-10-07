package com.ledgerflow.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.events.EventContracts;
import com.ledgerflow.ledger.LedgerTransaction;
import com.ledgerflow.ledger.LedgerTransactionRepository;
import com.ledgerflow.outbox.OutboxService;
import com.ledgerflow.projection.ProcessedEvent;
import com.ledgerflow.projection.ProcessedEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settlement lifecycle: creation from ledger events, provider callbacks, and
 * the resulting settlement events.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlements;
    private final LedgerTransactionRepository transactions;
    private final ProcessedEventRepository processedEvents;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final WebhookSigner signer;

    public SettlementService(SettlementRepository settlements,
                             LedgerTransactionRepository transactions,
                             ProcessedEventRepository processedEvents,
                             OutboxService outbox, ObjectMapper mapper,
                             WebhookSigner signer) {
        this.settlements = settlements;
        this.transactions = transactions;
        this.processedEvents = processedEvents;
        this.outbox = outbox;
        this.mapper = mapper;
        this.signer = signer;
    }

    /**
     * Creates the settlement for a posted ledger transaction. Idempotent on the
     * event id: redelivered ledger events must not create a second settlement.
     */
    @Transactional
    public void createFromLedgerEvent(UUID eventId, EventContracts.TransactionPosted event) {
        if (processedEvents.existsById(eventId)) {
            return;
        }
        LedgerTransaction tx = transactions.findById(event.transactionId())
                .orElseThrow(() -> new IllegalArgumentException("unknown transaction " + event.transactionId()));
        if (settlements.findByTransactionId(tx.getId()).isPresent()) {
            processedEvents.save(new ProcessedEvent(eventId, "settlement"));
            return;
        }
        // Settle the net outflow: sum of DEBIT postings (money leaving source accounts).
        long amount = event.postings().stream()
                .filter(p -> "DEBIT".equals(p.direction()))
                .mapToLong(EventContracts.PostingPayload::amountMinorUnits)
                .sum();
        String currency = event.postings().isEmpty() ? "USD" : event.postings().get(0).currency();
        SettlementScenario scenario = parseScenario(event.metadata().get("settlementScenario"));

        Settlement settlement = new Settlement(tx, amount, currency, scenario);
        settlements.save(settlement);

        var requested = new EventContracts.SettlementRequested(
                settlement.getId(), tx.getId(), amount, currency, scenario.name());
        outbox.stage("settlement", settlement.getId().toString(),
                EventContracts.SettlementRequested.TYPE, 1, writeJson(requested));
        processedEvents.save(new ProcessedEvent(eventId, "settlement"));
        log.info("settlement {} created for tx {} (scenario={})",
                settlement.getId(), tx.getId(), scenario);
    }

    /**
     * Applies a provider webhook callback. Verifies the HMAC signature first,
     * then applies idempotently: the provider may deliver the same callback
     * twice, and concurrent duplicate deliveries are serialized on the row lock.
     *
     * @return true if this delivery changed state, false if it was a duplicate
     */
    @Transactional
    public boolean handleWebhookCallback(String rawBody, String signature) {
        if (!signer.verify(rawBody, signature)) {
            throw new WebhookSignatureException();
        }
        JsonNode body = readJson(rawBody);
        UUID settlementId = UUID.fromString(body.get("settlementId").asText());
        String providerEventId = body.get("providerEventId").asText();
        String providerRef = body.get("providerRef").asText();

        Settlement settlement = settlements.lockById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("unknown settlement " + settlementId));

        // Duplicate delivery of an already-applied callback: no-op.
        if (providerEventId.equals(settlement.getProviderEventId())) {
            log.info("duplicate webhook ignored for settlement {}", settlementId);
            return false;
        }
        if (settlement.getStatus() == SettlementStatus.SETTLED) {
            log.warn("webhook for already-settled {} with different event id {}; keeping first",
                    settlementId, providerEventId);
            return false;
        }

        settlement.markSettled(providerRef, providerEventId);
        var completed = new EventContracts.SettlementCompleted(
                settlement.getId(), settlement.getTransaction().getId(),
                providerRef, Instant.now());
        outbox.stage("settlement", settlement.getId().toString(),
                EventContracts.SettlementCompleted.TYPE, 1, writeJson(completed));
        log.info("settlement {} confirmed by provider ref {}", settlementId, providerRef);
        return true;
    }

    @Transactional
    public void markFailed(UUID settlementId, String reason) {
        Settlement settlement = settlements.findById(settlementId)
                .orElseThrow(() -> new IllegalArgumentException("unknown settlement " + settlementId));
        if (settlement.getStatus() == SettlementStatus.FAILED) {
            return;
        }
        settlement.markFailed(reason);
        var failed = new EventContracts.SettlementFailed(
                settlement.getId(), settlement.getTransaction().getId(), reason);
        outbox.stage("settlement", settlement.getId().toString(),
                EventContracts.SettlementFailed.TYPE, 1, writeJson(failed));
    }

    private SettlementScenario parseScenario(String raw) {
        if (raw == null) {
            return SettlementScenario.SUCCESS;
        }
        try {
            return SettlementScenario.valueOf(raw);
        } catch (IllegalArgumentException e) {
            log.warn("unknown settlement scenario '{}', defaulting to SUCCESS", raw);
            return SettlementScenario.SUCCESS;
        }
    }

    private JsonNode readJson(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid webhook JSON", e);
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialization failed", e);
        }
    }
}
