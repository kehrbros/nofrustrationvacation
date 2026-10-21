package com.vacantagent.currency.data;

import com.vacantagent.currency.entities.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {
    Optional<ExchangeRate> findFirstByCurrencyAndRateDateLessThanEqualOrderByRateDateDesc(String currency, LocalDate date);

    boolean existsByCurrencyAndRateDate(String currency, LocalDate rateDate);
}
