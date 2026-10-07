package com.ledgerflow.common;

import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary value object.
 *
 * <p>Amounts are stored as <strong>minor units</strong> (e.g. cents) in a {@code long}.
 * Floating-point types are never used for authoritative money — binary floating point
 * cannot represent most decimal fractions exactly, which silently breaks the
 * {@code debits == credits} invariant at scale. See ADR-002.
 *
 * @param minorUnits amount in the currency's minor unit; must be non-negative
 * @param currency   ISO 4217 alphabetic code, e.g. "USD"
 */
public record Money(long minorUnits, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be an ISO 4217 code, got: " + currency);
        }
        // Validate against the JDK's ISO registry so typos like "UDS" fail fast.
        Currency.getInstance(currency);
        if (minorUnits < 0) {
            throw new IllegalArgumentException("money amount must be non-negative, got: " + minorUnits);
        }
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    /** Parses a major-unit decimal string, e.g. {@code Money.parse("19.99", "USD")}. */
    public static Money parse(String majorUnits, String currency) {
        Objects.requireNonNull(majorUnits, "majorUnits");
        int fractionDigits = Currency.getInstance(currency).getDefaultFractionDigits();
        long factor = (long) Math.pow(10, fractionDigits);
        String[] parts = majorUnits.split("\\.");
        long major = Long.parseLong(parts[0]);
        long minor = parts.length > 1 ? Long.parseLong((parts[1] + "00").substring(0, fractionDigits)) : 0;
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("money amount must be non-negative");
        }
        return new Money(major * factor + minor, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public boolean isGreaterThanOrEqual(Money other) {
        requireSameCurrency(other);
        return minorUnits >= other.minorUnits;
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: %s vs %s".formatted(currency, other.currency));
        }
    }

    @Override
    public String toString() {
        int fractionDigits = Currency.getInstance(currency).getDefaultFractionDigits();
        long factor = (long) Math.pow(10, fractionDigits);
        String pattern = "%d.%0" + fractionDigits + "d %s";
        return pattern.formatted(minorUnits / factor, minorUnits % factor, currency);
    }
}
