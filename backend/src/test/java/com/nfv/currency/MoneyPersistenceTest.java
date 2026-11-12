package com.nfv.currency;

import com.nfv.common.money.Money;
import com.nfv.currency.data.ExchangeRateRepository;
import com.nfv.currency.entities.ExchangeRate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class MoneyPersistenceTest {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Test
    void allMoneyColumnsHaveScale8() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT table_name, column_name, numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema = 'public' AND (column_name LIKE '%price%' OR column_name LIKE '%cost%'
                    OR column_name IN ('balance', 'amount', 'original_amount'))
                    AND table_name NOT IN ('booking', 'booking_aud', 'booking_reservation', 'advisor_proposal',
                        'advisor_proposal_step', 'plan_payment')""");

        // no money column exists since the accounting was removed (V54); the rule applies to new ones
        for (Map<String, Object> column : columns) {
            assertEquals(19, ((Number) column.get("NUMERIC_PRECISION")).intValue(), column.toString());
            assertEquals(Money.SCALE, ((Number) column.get("NUMERIC_SCALE")).intValue(), column.toString());
        }
    }

    @Test
    void latestRateUpToDateIsFound() {
        OffsetDateTime now = OffsetDateTime.now();
        exchangeRateRepository.save(new ExchangeRate("CHF", new BigDecimal("0.8"), LocalDate.of(2026, 10, 19), now, "TEST"));
        exchangeRateRepository.save(new ExchangeRate("CHF", new BigDecimal("0.9"), LocalDate.of(2026, 10, 20), now, "TEST"));

        assertEquals(new BigDecimal("0.9000000000"), exchangeRateRepository
                .findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("CHF", LocalDate.of(2026, 10, 25))
                .orElseThrow().getRate());
        assertEquals(new BigDecimal("0.8000000000"), exchangeRateRepository
                .findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc("CHF", LocalDate.of(2026, 10, 19))
                .orElseThrow().getRate());
    }
}
