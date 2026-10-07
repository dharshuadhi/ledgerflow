package com.ledgerflow.api;

import com.ledgerflow.api.dto.ApiDtos.AccountResponse;
import com.ledgerflow.api.dto.ApiDtos.BalanceResponse;
import com.ledgerflow.api.dto.ApiDtos.CreateAccountRequest;
import com.ledgerflow.api.dto.ApiDtos.PageResponse;
import com.ledgerflow.api.dto.ApiDtos.PostingResponse;
import com.ledgerflow.ledger.Account;
import com.ledgerflow.ledger.AccountRepository;
import com.ledgerflow.ledger.AccountStatus;
import com.ledgerflow.ledger.Posting;
import com.ledgerflow.ledger.PostingRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountRepository accounts;
    private final PostingRepository postings;
    private final com.ledgerflow.projection.BalanceProjectionService projections;

    public AccountController(AccountRepository accounts, PostingRepository postings,
                             com.ledgerflow.projection.BalanceProjectionService projections) {
        this.accounts = accounts;
        this.postings = postings;
        this.projections = projections;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Open a new ledger account")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest req) {
        String number = "ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Account account = new Account(number, req.ownerName(), req.currency());
        if (req.initialBalanceMinorUnits() > 0) {
            // Opening balances are owner equity, not created money: in a fuller
            // chart of accounts this would credit an equity account. Here the
            // opening balance seeds the cache and is disclosed as such.
            account.setBalanceMinorUnits(req.initialBalanceMinorUnits());
        }
        Account saved = accounts.save(account);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(saved));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Get account details")
    public AccountResponse get(@PathVariable UUID id) {
        return toResponse(accounts.findById(id)
                .orElseThrow(() -> new com.ledgerflow.ledger.AccountNotFoundException(id)));
    }

    @GetMapping("/{id}/statement")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Paginated posting history for an account")
    public PageResponse<PostingResponse> statement(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        accounts.findById(id).orElseThrow(() -> new com.ledgerflow.ledger.AccountNotFoundException(id));
        Page<Posting> postingsPage = postings.findByAccountIdOrderByCreatedAtDesc(
                id, PageRequest.of(page, Math.min(size, 100)));
        return new PageResponse<>(
                postingsPage.map(p -> new PostingResponse(p.getId(), p.getAccount().getId(),
                        p.getDirection().name(), p.getAmountMinorUnits(), p.getCurrency(),
                        p.getCreatedAt().toString())).toList(),
                page, size, postingsPage.getTotalElements(), postingsPage.getTotalPages());
    }

    @GetMapping("/{id}/balances")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Compare cached, authoritative (postings-derived), and projected balances")
    public BalanceResponse balances(@PathVariable UUID id) {
        Account account = accounts.findById(id)
                .orElseThrow(() -> new com.ledgerflow.ledger.AccountNotFoundException(id));
        long authoritative = postings.authoritativeBalance(id, account.getCurrency());
        Long projected = projections.projectedBalance(id).orElse(null);
        boolean consistent = authoritative == account.getBalanceMinorUnits();
        return new BalanceResponse(id, account.getCurrency(), account.getBalanceMinorUnits(),
                authoritative, projected, consistent);
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(a.getId(), a.getAccountNumber(), a.getOwnerName(),
                a.getCurrency(), a.getStatus().name(), a.getBalanceMinorUnits(),
                a.getCreatedAt().toString());
    }
}
