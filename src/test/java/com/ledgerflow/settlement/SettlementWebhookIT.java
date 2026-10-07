package com.ledgerflow.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.Account;
import com.ledgerflow.ledger.AccountRepository;
import com.ledgerflow.ledger.LedgerService;
import com.ledgerflow.common.Money;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Webhook security and idempotency: bad signatures are rejected before any
 * database access, and duplicate deliveries never double-apply.
 */
class SettlementWebhookIT extends AbstractIntegrationTest {

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementRepository settlements;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WebhookSigner signer;

    @Autowired
    private ObjectMapper mapper;

    private Settlement pendingSettlement() {
        Account a = accounts.save(new Account("WH-A", "owner", "USD"));
        a.setBalanceMinorUnits(10_000);
        a = accounts.save(a);
        Account b = accounts.save(new Account("WH-B", "owner", "USD"));
        var tx = ledger.postTransfer(a.getId(), b.getId(), Money.of(1_000, "USD"), "wh-key", "x");
        return settlements.save(new Settlement(tx, 1_000, "USD", SettlementScenario.SUCCESS));
    }

    private String[] signedBody(UUID settlementId, String providerEventId) {
        ObjectNode body = mapper.createObjectNode()
                .put("settlementId", settlementId.toString())
                .put("providerEventId", providerEventId)
                .put("providerRef", "REF-123")
                .put("status", "SETTLED");
        String raw = body.toString();
        return new String[]{raw, signer.sign(raw)};
    }

    @Test
    void validSignatureSettles() {
        Settlement s = pendingSettlement();
        String[] signed = signedBody(s.getId(), "prov-evt-1");

        boolean changed = settlementService.handleWebhookCallback(signed[0], signed[1]);

        assertTrue(changed);
        Settlement reloaded = settlements.findById(s.getId()).orElseThrow();
        assertEquals(SettlementStatus.SETTLED, reloaded.getStatus());
        assertEquals("prov-evt-1", reloaded.getProviderEventId());
    }

    @Test
    void duplicateCallbackIsNoOp() {
        Settlement s = pendingSettlement();
        String[] signed = signedBody(s.getId(), "prov-evt-2");

        assertTrue(settlementService.handleWebhookCallback(signed[0], signed[1]));
        assertFalse(settlementService.handleWebhookCallback(signed[0], signed[1]));

        assertEquals(SettlementStatus.SETTLED,
                settlements.findById(s.getId()).orElseThrow().getStatus());
    }

    @Test
    void invalidSignatureRejected() {
        Settlement s = pendingSettlement();
        String[] signed = signedBody(s.getId(), "prov-evt-3");

        assertThrows(WebhookSignatureException.class, () ->
                settlementService.handleWebhookCallback(signed[0], "tampered-signature"));
        assertThrows(WebhookSignatureException.class, () ->
                settlementService.handleWebhookCallback(signed[0], null));

        // Nothing was touched.
        assertEquals(SettlementStatus.PENDING,
                settlements.findById(s.getId()).orElseThrow().getStatus());
    }

    @Test
    void tamperedBodyFailsVerification() {
        Settlement s = pendingSettlement();
        String[] signed = signedBody(s.getId(), "prov-evt-4");
        String tampered = signed[0].replace("REF-123", "REF-999");

        assertThrows(WebhookSignatureException.class, () ->
                settlementService.handleWebhookCallback(tampered, signed[1]));
    }
}
