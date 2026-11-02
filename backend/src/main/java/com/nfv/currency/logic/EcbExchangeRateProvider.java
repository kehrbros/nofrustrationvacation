package com.nfv.currency.logic;

import com.nfv.common.errorhandling.TechnicalException;
import com.nfv.common.money.Money;
import com.nfv.currency.entities.ExchangeRate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily reference rates of the European Central Bank. They are based on EUR, rates for another base currency are
 * calculated as cross rates ({@code EUR->X / EUR->base}).
 */
@Component
public class EcbExchangeRateProvider implements ExchangeRateProvider {
    private static final String EUR = "EUR";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final String url;

    public EcbExchangeRateProvider(@Value("${nfv.money.exchange-rates.ecb-url}") String url) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(TIMEOUT);
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.url = url;
    }

    @Override
    public String source() {
        return "ECB";
    }

    @Override
    public List<ExchangeRateQuote> fetchLatest() {
        byte[] xml = restClient.get().uri(url).retrieve().body(byte[].class);
        if (xml == null) {
            throw new TechnicalException("Empty response from " + url);
        }
        return parse(xml);
    }

    static List<ExchangeRateQuote> parse(byte[] xml) {
        Element dailyCube;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            dailyCube = findDailyCube(document);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot parse ECB exchange rates", e);
        }
        LocalDate rateDate = LocalDate.parse(dailyCube.getAttribute("time"));

        Map<String, BigDecimal> eurRates = new HashMap<>();
        eurRates.put(EUR, BigDecimal.ONE);
        NodeList cubes = dailyCube.getElementsByTagName("Cube");
        for (int i = 0; i < cubes.getLength(); i++) {
            Element cube = (Element) cubes.item(i);
            eurRates.put(cube.getAttribute("currency"), new BigDecimal(cube.getAttribute("rate")));
        }

        String base = Money.BASE_CURRENCY.getCurrencyCode();
        BigDecimal eurToBase = eurRates.get(base);
        if (eurToBase == null) {
            throw new IllegalStateException("ECB exchange rates do not contain the base currency " + base);
        }
        List<ExchangeRateQuote> quotes = new ArrayList<>();
        eurRates.forEach((currency, eurRate) -> {
            if (!currency.equals(base)) {
                BigDecimal rate = eurRate.divide(eurToBase, MathContext.DECIMAL128)
                        .setScale(ExchangeRate.RATE_SCALE, RoundingMode.HALF_EVEN);
                quotes.add(new ExchangeRateQuote(Currency.getInstance(currency), rate, rateDate));
            }
        });
        return quotes;
    }

    private static Element findDailyCube(Document document) {
        NodeList cubes = document.getElementsByTagName("Cube");
        for (int i = 0; i < cubes.getLength(); i++) {
            Element cube = (Element) cubes.item(i);
            if (cube.hasAttribute("time")) {
                return cube;
            }
        }
        throw new IllegalStateException("No dated Cube element found");
    }
}
