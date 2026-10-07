package com.ledgerflow.api;

import com.ledgerflow.api.dto.ApiDtos.SettlementResponse;
import com.ledgerflow.settlement.Settlement;
import com.ledgerflow.settlement.SettlementRepository;
import com.ledgerflow.settlement.SettlementService;
import com.ledgerflow.settlement.WebhookSignatureException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settlements")
@Tag(name = "Settlements")
public class SettlementController {

    private final SettlementRepository settlements;
    private final SettlementService settlementService;

    public SettlementController(SettlementRepository settlements,
                                SettlementService settlementService) {
        this.settlements = settlements;
        this.settlementService = settlementService;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Get settlement status")
    public SettlementResponse get(@PathVariable UUID id) {
        return toResponse(settlements.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown settlement " + id)));
    }

    /**
     * Provider callback endpoint. Authenticated by HMAC signature
     * ({@code X-Signature} header), not by JWT — see SecurityConfig.
     */
    @PostMapping("/webhook")
    @Operation(summary = "Settlement provider webhook (HMAC-signed)",
            description = "Verifies the X-Signature HMAC before touching the database. "
                    + "Duplicate deliveries are idempotent.")
    public ResponseEntity<String> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Signature", required = false) String signature) {
        try {
            boolean changed = settlementService.handleWebhookCallback(rawBody, signature);
            return ResponseEntity.ok(changed ? "applied" : "duplicate");
        } catch (WebhookSignatureException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("invalid signature");
        }
    }

    private SettlementResponse toResponse(Settlement s) {
        return new SettlementResponse(s.getId(), s.getTransaction().getId(),
                s.getAmountMinorUnits(), s.getCurrency(), s.getStatus().name(),
                s.getProviderRef(), s.getAttempts(), s.getLastError(),
                s.getCreatedAt().toString());
    }
}
