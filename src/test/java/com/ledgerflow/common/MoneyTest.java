package com.ledgerflow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesMajorUnitsToMinorUnits() {
        assertEquals(1999L, Money.parse("19.99", "USD").minorUnits());
        assertEquals(100L, Money.parse("1", "USD").minorUnits());
        assertEquals(0L, Money.parse("0.00", "USD").minorUnits());
    }

    @Test
    void rejectsInvalidCurrencyCodes() {
        assertThrows(Exception.class, () -> Money.of(100, "UDS"));
        assertThrows(Exception.class, () -> Money.of(100, "usd"));
        assertThrows(Exception.class, () -> Money.of(100, "USDD"));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(-1, "USD"));
    }

    @Test
    void arithmeticRequiresSameCurrency() {
        Money a = Money.of(100, "USD");
        assertEquals(300L, a.add(Money.of(200, "USD")).minorUnits());
        assertThrows(IllegalArgumentException.class, () -> a.add(Money.of(200, "EUR")));
        assertTrue(a.isGreaterThanOrEqual(Money.of(100, "USD")));
    }

    @Test
    void addExactOverflowIsNotSilent() {
        assertThrows(ArithmeticException.class,
                () -> Money.of(Long.MAX_VALUE, "USD").add(Money.of(1, "USD")));
    }
}
