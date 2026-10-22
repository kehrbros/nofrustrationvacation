package com.vacantagent.accounting.api;

import com.vacantagent.accounting.dto.LedgerEntryDto;
import com.vacantagent.accounting.dto.PayoutAccountStatusDto;
import com.vacantagent.accounting.dto.PayoutDto;
import com.vacantagent.accounting.dto.TopUpDto;
import com.vacantagent.accounting.entities.LedgerEntry;
import com.vacantagent.accounting.entities.Payout;
import com.vacantagent.accounting.entities.PayoutAccount;
import com.vacantagent.accounting.entities.TopUp;
import com.vacantagent.common.money.CurrencyAmount;
import com.vacantagent.common.money.Money;
import com.vacantagent.common.money.OriginalAmount;
import com.vacantagent.currency.dto.MoneyDto;
import com.vacantagent.currency.logic.CurrencyConversion;
import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.currency.logic.MoneyRounding;
import com.vacantagent.paypal.PayPalConnect;
import com.vacantagent.user.logic.AppUserSettings;
import com.vacantagent.user.security.CurrentUser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Currency conversion of the accounting API, see Docs/money.md: balances in the requested or preferred currency,
 * bookings with the exchange rate stored with them.
 */
@Component
@RequiredArgsConstructor
class AccountingDtoMapper {
    private final CurrencyConversionService currencyConversionService;
    private final AppUserSettings appUserSettings;
    private final PayPalConnect payPalConnect;

    Currency responseCurrency(CurrentUser user, @Nullable String requested) {
        return currencyConversionService.responseCurrency(requested,
                appUserSettings.get(user.appUserId()).getPreferredCurrency());
    }

    /** Amount of a booking: in the base currency and as entered by the user, with the rate used. */
    record Booking(Money base, OriginalAmount original) {
    }

    /**
     * Converts an amount entered by the user for a booking (top-up, payout), with a rate not older than allowed.
     */
    Booking toBooking(@Nullable BigDecimal amount, @Nullable String currencyCode) {
        Currency currency = currencyConversionService.supportedCurrency(currencyCode);
        if (amount == null || amount.signum() <= 0) {
            throw new InvalidAmountException("Amount must be positive");
        }
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new InvalidAmountException(currency + " has " + currency.getDefaultFractionDigits() + " decimal places");
        }
        CurrencyConversion conversion = currencyConversionService.toBaseForBooking(
                new CurrencyAmount(amount.setScale(currency.getDefaultFractionDigits(), RoundingMode.UNNECESSARY), currency));
        return new Booking(conversion.base(), new OriginalAmount(conversion.original().amount(),
                currency.getCurrencyCode(), conversion.exchangeRate(), conversion.exchangeRateDate()));
    }

    MoneyDto toDto(Money money, Currency currency, MoneyRounding rounding) {
        return currencyConversionService.toDto(money, currency, rounding);
    }

    MoneyDto toDto(OriginalAmount amount) {
        return new MoneyDto(amount.amount().setScale(Currency.getInstance(amount.currency()).getDefaultFractionDigits(),
                RoundingMode.HALF_EVEN), amount.currency(), amount.exchangeRate(), amount.exchangeRateDate());
    }

    LedgerEntryDto toDto(LedgerEntry entry, Currency currency) {
        return new LedgerEntryDto(entry.getId(), entry.getAccount().getType(), entry.getType(),
                currencyConversionService.toDto(entry.getAmount(), currency, MoneyRounding.AMOUNT,
                        entry.getBookedAt().toLocalDate()),
                entry.getReferenceId(), entry.getBookedAt());
    }

    TopUpDto toDto(TopUp topUp, Currency currency) {
        MoneyDto credited = topUp.getPaid().currency().equals(currency.getCurrencyCode())
                ? toDto(topUp.getPaid())
                : currencyConversionService.toDto(topUp.getAmount(), currency, MoneyRounding.AMOUNT,
                topUp.getCreatedAt().toLocalDate());
        return new TopUpDto(topUp.getId(), topUp.getStatus(), credited, toDto(topUp.getPaid()), topUp.getPaypalOrderId(),
                topUp.getFailureReason(), topUp.getCreatedAt(), topUp.getCompletedAt());
    }

    PayoutDto toDto(Payout payout) {
        return new PayoutDto(payout.getId(), payout.getStatus(), toDto(payout.getPaidOut()), payout.getFailureReason(),
                payout.getRequestedAt(), payout.getCompletedAt());
    }

    PayoutAccountStatusDto toStatus(@Nullable PayoutAccount account) {
        boolean available = payPalConnect.isAvailable();
        if (account == null) {
            return new PayoutAccountStatusDto(false, available, null, null, null, null);
        }
        return new PayoutAccountStatusDto(true, available, account.getProvider(), account.getEmail(),
                account.isVerified(), account.getLinkedAt());
    }
}
