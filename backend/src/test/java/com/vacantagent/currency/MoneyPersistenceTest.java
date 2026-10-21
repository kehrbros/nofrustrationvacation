package com.vacantagent.currency;

import com.vacantagent.common.money.Money;
import com.vacantagent.currency.data.ExchangeRateRepository;
import com.vacantagent.currency.entities.ExchangeRate;
import com.vacantagent.user.data.AppUserRepository;
import com.vacantagent.user.entities.AppUser;
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
    private AppUserRepository appUserRepository;
    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Test
    void allMoneyColumnsHaveScale8() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT table_name, column_name, numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema = 'PUBLIC' AND (column_name LIKE '%PRICE%' OR column_name LIKE '%COST%'
                    OR column_name = 'ACCOUNT_BALANCE')""");

        assertEquals(9, columns.size(), columns.toString());
        for (Map<String, Object> column : columns) {
            assertEquals(19, ((Number) column.get("NUMERIC_PRECISION")).intValue(), column.toString());
            assertEquals(Money.SCALE, ((Number) column.get("NUMERIC_SCALE")).intValue(), column.toString());
        }
    }

    @Test
    void moneyIsStoredWithFullPrecision() {
        AppUser user = appUserRepository.save(AppUser.firstLogin("money-precision", null, null));
        jdbcTemplate.update("UPDATE app_user SET account_balance = 0.00000075 WHERE id = ?", user.getId());

        assertEquals(Money.of("0.00000075"), appUserRepository.findById(user.getId()).orElseThrow().getAccountBalance());
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
