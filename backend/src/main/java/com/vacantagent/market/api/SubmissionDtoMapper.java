package com.vacantagent.market.api;

import com.vacantagent.currency.logic.CurrencyConversionService;
import com.vacantagent.currency.logic.MoneyRounding;
import com.vacantagent.market.dto.AgreementDto;
import com.vacantagent.market.dto.SubmissionDto;
import com.vacantagent.market.dto.SubmissionDto.SubmissionStatus;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.user.logic.AppUserSettings;
import com.vacantagent.user.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Currency;

/** Maps matching results to tool results and HTTP responses. Agreed prices are shown in the user's preferred currency. */
@Component
@RequiredArgsConstructor
public class SubmissionDtoMapper {
    private final AppUserSettings appUserSettings;
    private final CurrencyConversionService currencyConversionService;

    /** Call on the request thread; the match result may complete on the engine or writer thread. */
    public Currency responseCurrency(CurrentUser user) {
        return currencyConversionService.responseCurrency(null, appUserSettings.get(user.appUserId()).getPreferredCurrency());
    }

    /** The persisted id is returned in every case. */
    public SubmissionDto toDto(long id, MatchResult result, Currency currency) {
        return switch (result) {
            case MatchResult.Matched matched ->
                    new SubmissionDto(id, SubmissionStatus.MATCHED, null, toDto(matched.agreement(), currency));
            case MatchResult.Resting resting -> new SubmissionDto(id, SubmissionStatus.RESTING, null, null);
            case MatchResult.Rejected rejected -> new SubmissionDto(id, SubmissionStatus.REJECTED, rejected.reason(), null);
            case MatchResult.Cancelled cancelled -> new SubmissionDto(id, SubmissionStatus.CANCELLED, null, null);
            default -> throw new IllegalStateException("Unexpected result for a submission: " + result);
        };
    }

    /** 201 for matched or resting, 422 for rejected. The persisted id is returned in every case. */
    public ResponseEntity<SubmissionDto> toResponse(long id, MatchResult result, Currency currency) {
        SubmissionDto dto = toDto(id, result, currency);
        return ResponseEntity.status(dto.status() == SubmissionStatus.REJECTED ? 422 : HttpStatus.CREATED.value())
                .body(dto);
    }

    /** 204 if removed from the book, 404 if it was not waiting. */
    public static ResponseEntity<Void> toCancelResponse(MatchResult result) {
        return switch (result) {
            case MatchResult.Cancelled cancelled -> ResponseEntity.noContent().build();
            case MatchResult.NotFound notFound -> ResponseEntity.notFound().build();
            default -> throw new IllegalStateException("Unexpected result for a cancel: " + result);
        };
    }

    private AgreementDto toDto(Agreement agreement, Currency currency) {
        return new AgreementDto(agreement.getId(), agreement.getConsumerOrder().getId(),
                agreement.getProviderOffer().getId(), agreement.getProviderOffer().getModelProvider(),
                agreement.getProviderOffer().getModelName(), agreement.getAgreedAt(), agreement.getValidUntil(),
                currencyConversionService.toDto(agreement.getAgreedPricePerMillionInputTokens(), currency, MoneyRounding.PRICE),
                currencyConversionService.toDto(agreement.getAgreedPricePerMillionOutputTokens(), currency, MoneyRounding.PRICE));
    }
}