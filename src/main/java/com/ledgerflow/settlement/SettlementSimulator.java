package com.ledgerflow.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulates an external settlement provider.
 *
 * <p>This is deliberately <strong>not</strong> a real bank integration — it is a
 * deterministic stand-in that behaves like one, so failure handling can be
 * demonstrated and tested without moving real money. Every callback goes
 * through the same HMAC-signed webhook path a real provider would use.
 */
@Component
public class SettlementSimulator {

    private static final Logger log = LoggerFactory.getLogger(SettlementSimulator.class);
    private static final Duration DELAYED_AFTER = Duration.ofSeconds(30);

    private final SettlementRepository settlements;
    private final SettlementService settlementService;
    private final WebhookSigner signer;
    private final ObjectMapper mapper;

    public SettlementSimulator(SettlementRepository settlements,
                               SettlementService settlementService,
                               WebhookSigner signer, ObjectMapper mapper) {
        this.settlements = settlements;
        this.settlementService = settlementService;
        this.signer = signer;
        this.mapper = mapper;
    }

    /** Entry point for the {@code settlement.requested} consumer. */
    public void onSettlementRequested(UUID settlementId) {
        Settlement settlement = settlements.findById(settlementId).orElse(null);
        if (settlement == null || settlement.getStatus() != SettlementStatus.PENDING) {
            return;
        }
        settlement.markProcessing();
        settlements.save(settlement);

        switch (settlement.getScenario()) {
            case SUCCESS -> deliverSuccessCallback(settlement, false);
            case DUPLICATE_CALLBACK -> {
                deliverSuccessCallback(settlement, false);
                deliverSuccessCallback(settlement, true); // same providerEventId: must no-op
            }
            case FAIL -> settlementService.markFailed(settlementId,
                    "provider rejected settlement: invalid beneficiary account");
            case TIMEOUT -> log.info("simulator: no response for {} (TIMEOUT scenario)", settlementId);
            case DELAYED -> {
                settlement.setNotBefore(Instant.now().plus(DELAYED_AFTER));
                settlements.save(settlement);
                log.info("simulator: {} will confirm after {}", settlementId, DELAYED_AFTER);
            }
            case PROVIDER_OUTAGE -> {
                settlement.recordAttemptError("provider unavailable (simulated outage)");
                settlements.save(settlement);
                log.warn("simulator: provider outage for {}", settlementId);
            }
        }
    }

    /** Completes DELAYED settlements whose wait has elapsed. */
    @Scheduled(fixedDelayString = "${ledgerflow.settlement.delayed-poll-ms:5000}")
    @Transactional
    public void completeDelayed() {
        List<Settlement> due = settlements.findByStatus(SettlementStatus.PROCESSING).stream()
                .filter(s -> s.getScenario() == SettlementScenario.DELAYED
                        && s.getNotBefore() != null
                        && !s.getNotBefore().isAfter(Instant.now()))
                .toList();
        for (Settlement s : due) {
            deliverSuccessCallback(s, false);
        }
    }

    private void deliverSuccessCallback(Settlement settlement, boolean duplicate) {
        String providerEventId = duplicate && settlement.getProviderEventId() != null
                ? settlement.getProviderEventId()
                : "prov-" + UUID.randomUUID();
        String providerRef = "REF-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();

        ObjectNode body = mapper.createObjectNode()
                .put("settlementId", settlement.getId().toString())
                .put("providerEventId", providerEventId)
                .put("providerRef", providerRef)
                .put("status", "SETTLED");
        String rawBody = body.toString();
        String signature = signer.sign(rawBody);

        boolean changed = settlementService.handleWebhookCallback(rawBody, signature);
        log.info("simulator: {} callback for {} (changed={})",
                duplicate ? "duplicate" : "success", settlement.getId(), changed);
    }
}
