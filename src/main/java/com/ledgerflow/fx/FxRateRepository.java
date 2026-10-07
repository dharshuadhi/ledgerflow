package com.ledgerflow.fx;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FxRateRepository extends JpaRepository<FxRate, UUID> {

    @Query("select r from FxRate r where r.provider = :provider and r.baseCurrency = :base"
            + " and r.quoteCurrency = :quote order by r.rateDate desc")
    List<FxRate> findLatest(String provider, String base, String quote);

    default Optional<FxRate> findNewest(String provider, String base, String quote) {
        List<FxRate> all = findLatest(provider, base, quote);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    boolean existsByProviderAndBaseCurrencyAndQuoteCurrencyAndRateDate(
            String provider, String base, String quote, LocalDate rateDate);
}
