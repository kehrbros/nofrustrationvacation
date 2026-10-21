package com.vacantagent.currency.logic;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EcbExchangeRateProviderTest {
    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                <gesmes:subject>Reference rates</gesmes:subject>
                <Cube>
                    <Cube time="2026-10-20">
                        <Cube currency="USD" rate="1.25"/>
                        <Cube currency="JPY" rate="150"/>
                        <Cube currency="GBP" rate="0.875"/>
                    </Cube>
                </Cube>
            </gesmes:Envelope>
            """;

    @Test
    void calculatesCrossRatesToBaseCurrency() {
        List<ExchangeRateQuote> quotes = EcbExchangeRateProvider.parse(XML.getBytes(StandardCharsets.UTF_8));

        Map<String, BigDecimal> rates = quotes.stream()
                .collect(Collectors.toMap(q -> q.currency().getCurrencyCode(), ExchangeRateQuote::rate));
        assertEquals(new BigDecimal("0.8000000000"), rates.get("EUR"));
        assertEquals(new BigDecimal("120.0000000000"), rates.get("JPY"));
        assertEquals(new BigDecimal("0.7000000000"), rates.get("GBP"));
        assertFalse(rates.containsKey("USD"), "no rate for the base currency itself");
        quotes.forEach(q -> assertEquals(LocalDate.of(2026, 10, 20), q.rateDate()));
    }
}
