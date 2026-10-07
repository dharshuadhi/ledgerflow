package com.ledgerflow.api;

import com.ledgerflow.api.dto.ApiDtos.FxRateResponse;
import com.ledgerflow.fx.FxRate;
import com.ledgerflow.fx.FxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fx")
@Tag(name = "Foreign Exchange")
public class FxController {

    private final FxService fx;
    private final Duration staleAfter;

    public FxController(FxService fx,
                        @Value("${ledgerflow.fx.stale-after-hours:72}") long staleAfterHours) {
        this.fx = fx;
        this.staleAfter = Duration.ofHours(staleAfterHours);
    }

    @GetMapping("/rates")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Latest reference rate for a quote currency (EUR-based, ECB)")
    public FxRateResponse rate(@RequestParam String quote) {
        FxRate r = fx.newestRate(quote.toUpperCase())
                .orElseThrow(() -> new com.ledgerflow.fx.FxRateUnavailableException(quote));
        boolean stale = r.getFetchedAt().plus(staleAfter).isBefore(Instant.now());
        return new FxRateResponse(r.getProvider(), r.getBaseCurrency(), r.getQuoteCurrency(),
                r.getRateDate().toString(), r.getRate().toPlainString(),
                r.getFetchedAt().toString(), stale);
    }

    @GetMapping("/convert")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "Quote a currency conversion (does not move money)")
    public FxService.ConversionQuote convert(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam long amountMinorUnits) {
        return fx.convert(from.toUpperCase(), to.toUpperCase(), amountMinorUnits);
    }

    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Refresh rates from the ECB feed now")
    public void refresh() {
        fx.refresh();
    }
}
