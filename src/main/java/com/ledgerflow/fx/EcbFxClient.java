package com.ledgerflow.fx;

import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * Fetches the European Central Bank euro foreign-exchange reference rates.
 *
 * <p>Source: {@code https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml}
 * — free, no authentication, updated every working day around 16:00 CET.
 * Rates are quoted as "1 EUR = X quote currency".
 *
 * <p>Fetch failures never produce synthetic rates: the caller keeps serving the
 * last ingested rates flagged as stale, and conversion fails loudly only when
 * no rate was ever ingested.
 */
@Component
public class EcbFxClient {

    private static final Logger log = LoggerFactory.getLogger(EcbFxClient.class);
    static final String ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    static final String PROVIDER = "ECB";

    private final HttpClient http;

    public EcbFxClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record EcbRate(String quoteCurrency, LocalDate rateDate, BigDecimal rate) {
    }

    /** Fetches and parses the daily feed. Throws on any failure — no silent fallbacks. */
    public List<EcbRate> fetchDaily() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(ECB_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/xml")
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("ECB feed returned HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /** Parses the ECB envelope: {@code <Cube time="..."><Cube currency rate/>...}. */
    static List<EcbRate> parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Harden against XXE: the feed is trusted, but parsers shouldn't be.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setExpandEntityReferences(false);
        var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));

        List<EcbRate> rates = new ArrayList<>();
        NodeList days = doc.getElementsByTagName("Cube");
        for (int i = 0; i < days.getLength(); i++) {
            Element day = (Element) days.item(i);
            if (!day.hasAttribute("time")) {
                continue;
            }
            LocalDate date = LocalDate.parse(day.getAttribute("time"));
            NodeList children = day.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j) instanceof Element quote) {
                    rates.add(new EcbRate(
                            quote.getAttribute("currency"),
                            date,
                            new BigDecimal(quote.getAttribute("rate"))));
                }
            }
        }
        if (rates.isEmpty()) {
            throw new IllegalStateException("ECB feed contained no rates");
        }
        log.info("parsed {} ECB rates", rates.size());
        return rates;
    }
}
