package com.ledgerflow.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FX rate ingestion and conversion.
 *
 * <p>All ECB rates are EUR-based. Converting A→B goes through EUR:
 * {@code amount(B) = amount(A) / rate(A) * rate(B)}, rounded to the target
 * currency's minor units with {@link RoundingMode#HALF_EVEN} (banker's rounding
 * — no systematic upward bias).
 *
 * <p>Staleness policy: rates older than {@code stale-after} are still served
 * but flagged {@code stale=true}; if no rate was ever ingested, conversion
 * fails loudly instead of inventing a number.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    private final EcbFxClient ecb;
    private final FxRateRepository rates;
    private final Duration staleAfter;

    public FxService(EcbFxClient ecb, FxRateRepository rates,
                     @Value("${ledgerflow.fx.stale-after-hours:72}") long staleAfterHours) {
        this.ecb = ecb;
        this.rates = rates;
        this.staleAfter = Duration.ofHours(staleAfterHours);
    }

    public record ConversionQuote(
            String fromCurrency, String toCurrency,
            long fromMinorUnits, long toMinorUnits,
            String rate, String rateDate, String provider, boolean stale) {
    }

    @Scheduled(cron = "${ledgerflow.fx.refresh-cron:0 0 17 * * *}")
    public void scheduledRefresh() {
        refresh();
    }

    /** Ingests the latest ECB feed; failed fetches keep old rates (flagged stale). */
    @Transactional
    public int refresh() {
        List<EcbFxClient.EcbRate> fetched;
        try {
            fetched = ecb.fetchDaily();
        } catch (Exception e) {
            log.error("ECB refresh failed; keeping previously ingested rates", e);
            return 0;
        }
        int stored = 0;
        for (EcbFxClient.EcbRate r : fetched) {
            if (!rates.existsByProviderAndBaseCurrencyAndQuoteCurrencyAndRateDate(
                    EcbFxClient.PROVIDER, "EUR", r.quoteCurrency(), r.rateDate())) {
                rates.save(new FxRate(EcbFxClient.PROVIDER, "EUR",
                        r.quoteCurrency(), r.rateDate(), r.rate()));
                stored++;
            }
        }
        log.info("ECB refresh: {} new rates stored", stored);
        return stored;
    }

    /** Converts minor units of one currency to minor units of another. Quote only. */
    @Transactional(readOnly = true)
    public ConversionQuote convert(String from, String to, long fromMinorUnits) {
        if (from.equals(to)) {
            FxRate same = newestRate(from).orElse(null);
            return new ConversionQuote(from, to, fromMinorUnits, fromMinorUnits, "1",
                    same != null ? same.getRateDate().toString() : null,
                    EcbFxClient.PROVIDER, same != null && isStale(same));
        }
        BigDecimal rateFrom = eurRate(from); // 1 EUR = rateFrom FROM
        BigDecimal rateTo = eurRate(to);     // 1 EUR = rateTo TO
        // amount(TO) = amount(FROM) / rateFrom * rateTo, rounded to minor units
        BigDecimal converted = BigDecimal.valueOf(fromMinorUnits)
                .divide(rateFrom, 10, RoundingMode.HALF_EVEN)
                .multiply(rateTo)
                .setScale(0, RoundingMode.HALF_EVEN);

        FxRate anchor = newestRate(to).orElse(newestRate(from).orElse(null));
        boolean stale = anchor == null || isStale(anchor);
        String crossRate = rateTo.divide(rateFrom, 8, RoundingMode.HALF_EVEN).toPlainString();
        return new ConversionQuote(from, to, fromMinorUnits, converted.longValueExact(),
                crossRate,
                anchor != null ? anchor.getRateDate().toString() : null,
                EcbFxClient.PROVIDER, stale);
    }

    @Transactional(readOnly = true)
    public Optional<FxRate> newestRate(String quoteCurrency) {
        if ("EUR".equals(quoteCurrency)) {
            return Optional.empty(); // EUR is the base; rate is definitionally 1
        }
        return rates.findNewest(EcbFxClient.PROVIDER, "EUR", quoteCurrency);
    }

    private BigDecimal eurRate(String currency) {
        if ("EUR".equals(currency)) {
            return BigDecimal.ONE;
        }
        return newestRate(currency)
                .orElseThrow(() -> new FxRateUnavailableException(currency))
                .getRate();
    }

    private boolean isStale(FxRate rate) {
        return rate.getFetchedAt().plus(staleAfter).isBefore(Instant.now());
    }

    /** Test seam: persist a rate directly (deterministic tests without HTTP). */
    @Transactional
    public void ingest(String quoteCurrency, LocalDate rateDate, BigDecimal rate) {
        rates.save(new FxRate(EcbFxClient.PROVIDER, "EUR", quoteCurrency, rateDate, rate));
    }
}
