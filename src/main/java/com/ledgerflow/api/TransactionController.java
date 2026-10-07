package com.ledgerflow.api;

import com.ledgerflow.api.dto.ApiDtos.PageResponse;
import com.ledgerflow.api.dto.ApiDtos.PostingResponse;
import com.ledgerflow.api.dto.ApiDtos.TransactionResponse;
import com.ledgerflow.ledger.LedgerTransaction;
import com.ledgerflow.ledger.LedgerTransactionRepository;
import com.ledgerflow.ledger.PostingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions")
public class TransactionController {

    private final LedgerTransactionRepository transactions;
    private final PostingRepository postings;

    public TransactionController(LedgerTransactionRepository transactions,
                                 PostingRepository postings) {
        this.transactions = transactions;
        this.postings = postings;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Get a transaction with its postings")
    public TransactionResponse get(@PathVariable UUID id) {
        LedgerTransaction tx = transactions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown transaction " + id));
        var postingDtos = postings.findByTransactionId(id).stream()
                .map(p -> new PostingResponse(p.getId(), p.getAccount().getId(),
                        p.getDirection().name(), p.getAmountMinorUnits(),
                        p.getCurrency(), p.getCreatedAt().toString()))
                .toList();
        return new TransactionResponse(tx.getId(), tx.getType().name(), tx.getStatus().name(),
                tx.getDescription(), tx.getCreatedAt().toString(), postingDtos);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "List transactions, newest first")
    public PageResponse<TransactionResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = transactions.findAll(PageRequest.of(page, Math.min(size, 100)));
        var content = result.getContent().stream()
                .map(tx -> new TransactionResponse(tx.getId(), tx.getType().name(),
                        tx.getStatus().name(), tx.getDescription(),
                        tx.getCreatedAt().toString(), java.util.List.of()))
                .toList();
        return new PageResponse<>(content, page, size,
                result.getTotalElements(), result.getTotalPages());
    }
}
