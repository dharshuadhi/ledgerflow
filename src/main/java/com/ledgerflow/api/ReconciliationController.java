package com.ledgerflow.api;

import com.ledgerflow.api.dto.ApiDtos.MismatchResponse;
import com.ledgerflow.api.dto.ApiDtos.PageResponse;
import com.ledgerflow.api.dto.ApiDtos.ReconciliationRunResponse;
import com.ledgerflow.reconciliation.ReconciliationMismatch;
import com.ledgerflow.reconciliation.ReconciliationRun;
import com.ledgerflow.reconciliation.ReconciliationRunRepository;
import com.ledgerflow.reconciliation.Reconciler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reconciliation")
@Tag(name = "Reconciliation")
public class ReconciliationController {

    private final Reconciler reconciler;
    private final ReconciliationRunRepository runs;
    private final com.ledgerflow.reconciliation.ReconciliationMismatchRepository mismatches;

    public ReconciliationController(Reconciler reconciler, ReconciliationRunRepository runs,
                                    com.ledgerflow.reconciliation.ReconciliationMismatchRepository mismatches) {
        this.reconciler = reconciler;
        this.runs = runs;
        this.mismatches = mismatches;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Trigger a reconciliation run now")
    public ReconciliationRunResponse trigger() {
        return toResponse(reconciler.run());
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "List reconciliation runs")
    public PageResponse<ReconciliationRunResponse> listRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = runs.findAllByOrderByStartedAtDesc(PageRequest.of(page, Math.min(size, 100)));
        var content = result.getContent().stream().map(this::toResponse).toList();
        return new PageResponse<>(content, page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping("/mismatches")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "List unresolved mismatches")
    public PageResponse<MismatchResponse> openMismatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<ReconciliationMismatch> list = reconciler.openMismatches(page, Math.min(size, 100));
        var content = list.stream().map(m -> new MismatchResponse(
                m.getId(), m.getRun().getId(), m.getMismatchType().name(),
                m.getTransaction() != null ? m.getTransaction().getId() : null,
                m.getSettlement() != null ? m.getSettlement().getId() : null,
                m.getDetails(), m.getCreatedAt().toString(), m.getResolvedAt() != null)).toList();
        return new PageResponse<>(content, page, size, content.size(), 1);
    }

    @PostMapping("/mismatches/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mark a mismatch resolved (audited)")
    public void resolve(@PathVariable UUID id) {
        var mismatch = mismatches.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown mismatch " + id));
        mismatch.resolve();
        mismatches.save(mismatch);
    }

    private ReconciliationRunResponse toResponse(ReconciliationRun r) {
        return new ReconciliationRunResponse(r.getId(), r.getStatus().name(),
                r.getCheckedCount(), r.getMismatchCount(),
                r.getStartedAt().toString(),
                r.getFinishedAt() != null ? r.getFinishedAt().toString() : null);
    }
}
