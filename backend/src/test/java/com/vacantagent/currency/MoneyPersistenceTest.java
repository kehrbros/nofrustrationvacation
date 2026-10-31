package com.vacantagent.currency;

import com.vacantagent.accounting.data.AccountRepository;
import com.vacantagent.accounting.entities.AccountType;
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
    @Autowired
    private AccountRepository accountRepository;

    @Test
    void allMoneyColumnsHaveScale8() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                SELECT table_name, column_name, numeric_precision, numeric_scale FROM information_schema.columns
                WHERE table_schema = 'public' AND (column_name LIKE '%price%' OR column_name LIKE '%cost%'
                    OR column_name IN ('balance', 'amount', 'original_amount'))""");

        assertEquals(10, columns.size(), columns.toString());
        for (Map<String, Object> column : columns) {
            assertEquals(19, ((Number) column.get("NUMERIC_PRECISION")).intValue(), column.toString());
            assertEquals(Money.SCALE, ((Number) column.get("NUMERIC_SCALE")).intValue(), column.toString());
        }
    }

    @Test
    void moneyIsStoredWithFullPrecision() {
        AppUser user = appUserRepository.save(AppUser.firstLogin("money-precision", null, null));
        jdbcTemplate.update("INSERT INTO accounting_account (app_user_id, type, balance, created_at) VALUES (?, 'CREDIT', 0.00000075, CURRENT_TIMESTAMP)",
                user.getId());

        assertEquals(Money.of("0.00000075"), accountRepository.findByAppUserIdAndType(user.getId(), AccountType.CREDIT)
                .orElseThrow().getBalance());
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
