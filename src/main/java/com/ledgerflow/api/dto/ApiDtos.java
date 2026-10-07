package com.ledgerflow.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** Request/response DTOs. Amounts are minor units; currencies are ISO 4217. */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateAccountRequest(
            @NotBlank String ownerName,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO 4217") String currency,
            @Min(0) long initialBalanceMinorUnits) {
    }

    public record AccountResponse(
            UUID id,
            String accountNumber,
            String ownerName,
            String currency,
            String status,
            long balanceMinorUnits,
            String createdAt) {
    }

    public record CreateTransferRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destAccountId,
            @Min(1) long amountMinorUnits,
            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO 4217") String currency,
            String description,
            @Pattern(regexp = "SUCCESS|FAIL|TIMEOUT|DELAYED|DUPLICATE_CALLBACK|PROVIDER_OUTAGE",
                    message = "unknown settlement scenario") String settlementScenario) {
    }

    public record TransferResponse(
            UUID transactionId,
            String status,
            long amountMinorUnits,
            String currency,
            boolean replayed) {
    }

    public record PostingResponse(
            UUID id,
            UUID accountId,
            String direction,
            long amountMinorUnits,
            String currency,
            String createdAt) {
    }

    public record TransactionResponse(
            UUID id,
            String type,
            String status,
            String description,
            String createdAt,
            java.util.List<PostingResponse> postings) {
    }

    public record BalanceResponse(
            UUID accountId,
            String currency,
            long cachedBalanceMinorUnits,
            long authoritativeBalanceMinorUnits,
            Long projectedBalanceMinorUnits,
            boolean cacheConsistent) {
    }

    public record PageResponse<T>(
            java.util.List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }

    public record SettlementResponse(
            UUID id,
            UUID transactionId,
            long amountMinorUnits,
            String currency,
            String status,
            String providerRef,
            int attempts,
            String lastError,
            String createdAt) {
    }

    public record ReconciliationRunResponse(
            UUID id,
            String status,
            int checkedCount,
            int mismatchCount,
            String startedAt,
            String finishedAt) {
    }

    public record MismatchResponse(
            UUID id,
            UUID runId,
            String mismatchType,
            UUID transactionId,
            UUID settlementId,
            String details,
            String createdAt,
            boolean resolved) {
    }

    public record FxRateResponse(
            String provider,
            String baseCurrency,
            String quoteCurrency,
            String rateDate,
            String rate,
            String fetchedAt,
            boolean stale) {
    }

    public record ErrorResponse(
            String type,
            String title,
            int status,
            String detail,
            String correlationId) {
    }
}
