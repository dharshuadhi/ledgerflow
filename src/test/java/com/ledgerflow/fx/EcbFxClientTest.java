package com.ledgerflow.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EcbFxClientTest {

    private static final String SAMPLE_FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01"
                             xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
              <gesmes:subject>Reference rates</gesmes:subject>
              <Cube>
                <Cube time="2026-10-06">
                  <Cube currency="USD" rate="1.0852"/>
                  <Cube currency="JPY" rate="162.34"/>
                  <Cube currency="GBP" rate="0.8421"/>
                </Cube>
              </Cube>
            </gesmes:Envelope>
            """;

    @Test
    void parsesEcbEnvelope() throws Exception {
        var rates = EcbFxClient.parse(SAMPLE_FEED);
        assertEquals(3, rates.size());
        var usd = rates.stream().filter(r -> r.quoteCurrency().equals("USD")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("1.0852"), usd.rate());
        assertEquals(LocalDate.of(2026, 10, 6), usd.rateDate());
    }

    @Test
    void emptyFeedRejected() {
        assertThrows(Exception.class, () -> EcbFxClient.parse("<Cube></Cube>"));
    }

    @Test
    void conversionMathIsExplicit() {
        // 1 EUR = 1.0852 USD ; 1 EUR = 0.8421 GBP
        // 100.00 USD -> EUR -> GBP: 100/1.0852*0.8421 = 77.5957... -> 7759.57... minor
        BigDecimal rateFrom = new BigDecimal("1.0852");
        BigDecimal rateTo = new BigDecimal("0.8421");
        BigDecimal converted = BigDecimal.valueOf(10_000)
                .divide(rateFrom, 10, java.math.RoundingMode.HALF_EVEN)
                .multiply(rateTo)
                .setScale(0, java.math.RoundingMode.HALF_EVEN);
        assertEquals(7_760L, converted.longValueExact());
        assertTrue(converted.longValueExact() > 0);
    }
}
