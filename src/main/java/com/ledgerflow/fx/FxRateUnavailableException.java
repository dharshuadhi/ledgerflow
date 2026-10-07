package com.ledgerflow.fx;

/** No reference rate was ever ingested for this currency. Maps to HTTP 424-ish 422. */
public class FxRateUnavailableException extends RuntimeException {
    public FxRateUnavailableException(String currency) {
        super("no exchange rate available for " + currency
                + " (feed unreachable and no cached rate ingested)");
    }
}
