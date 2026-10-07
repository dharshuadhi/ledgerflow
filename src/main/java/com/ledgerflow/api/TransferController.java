package com.ledgerflow.api;

import com.ledgerflow.api.dto.ApiDtos.CreateTransferRequest;
import com.ledgerflow.api.dto.ApiDtos.TransferResponse;
import com.ledgerflow.transfer.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','SERVICE')")
    @Operation(summary = "Move money between accounts (idempotent)",
            description = "Requires an Idempotency-Key header. Retries with the same key and "
                    + "identical body replay the original response without moving money twice.")
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest req) {
        var result = transfers.transfer(new TransferService.TransferCommand(
                req.sourceAccountId(), req.destAccountId(), req.amountMinorUnits(),
                req.currency(), req.description(), idempotencyKey, req.settlementScenario()));
        var body = new TransferResponse(result.transactionId(), result.status(),
                result.amountMinorUnits(), result.currency(), result.replayed());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotent-Replay", "false").body(body);
    }
}
