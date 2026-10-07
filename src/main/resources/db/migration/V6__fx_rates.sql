-- V6: foreign-exchange reference rates.
--
-- Rates are NUMERIC here deliberately: they are reference data, not money.
-- Conversion always rounds to minor units explicitly at use time (see FxService).
-- Source metadata is persisted so a rate can never be mistaken for something
-- we invented: provider, base/quote pair, the rate's effective date, and when
-- we ingested it.

CREATE TABLE fx_rates (
    id             UUID PRIMARY KEY,
    provider       VARCHAR(32) NOT NULL, -- e.g. 'ECB'
    base_currency  CHAR(3) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    rate_date      DATE NOT NULL,
    rate           NUMERIC(18, 8) NOT NULL,
    fetched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_fx_rate UNIQUE (provider, base_currency, quote_currency, rate_date),
    CONSTRAINT chk_fx_rate_pos CHECK (rate > 0)
);
CREATE INDEX ix_fx_rates_lookup ON fx_rates(base_currency, quote_currency, rate_date DESC);
