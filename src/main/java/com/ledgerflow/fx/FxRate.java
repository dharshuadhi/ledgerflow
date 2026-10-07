package com.ledgerflow.fx;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A foreign-exchange reference rate with full source metadata.
 *
 * <p>Rates are reference data, not money: {@code NUMERIC} is appropriate here.
 * Every conversion rounds explicitly to minor units at use time. The metadata
 * (provider, pair, effective date, ingestion time) means a rate can never be
 * mistaken for a number we invented.
 */
@Entity
@Table(name = "fx_rates")
public class FxRate {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 3)
    private String baseCurrency;

    @Column(nullable = false, length = 3)
    private String quoteCurrency;

    @Column(nullable = false)
    private LocalDate rateDate;

    @Column(nullable = false, precision = 18, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false)
    private Instant fetchedAt;

    protected FxRate() {
    }

    public FxRate(String provider, String baseCurrency, String quoteCurrency,
                  LocalDate rateDate, BigDecimal rate) {
        this.id = UUID.randomUUID();
        this.provider = provider;
        this.baseCurrency = baseCurrency;
        this.quoteCurrency = quoteCurrency;
        this.rateDate = rateDate;
        this.rate = rate;
        this.fetchedAt = Instant.now();
    }

    public String getProvider() { return provider; }
    public String getBaseCurrency() { return baseCurrency; }
    public String getQuoteCurrency() { return quoteCurrency; }
    public LocalDate getRateDate() { return rateDate; }
    public BigDecimal getRate() { return rate; }
    public Instant getFetchedAt() { return fetchedAt; }
}
