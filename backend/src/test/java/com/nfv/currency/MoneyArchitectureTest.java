package com.nfv.currency;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.nfv.bookings.entities.Booking;
import com.nfv.bookings.entities.BookingReservation;
import com.nfv.common.money.CurrencyAmount;
import com.nfv.currency.dto.MoneyDto;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The backend calculates in the base currency only, other currencies exist at the API boundary, see Docs/money.md.
 */
class MoneyArchitectureTest {
    private static final String CURRENCY_MODULE = "com.nfv.currency..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nfv");

    @Test
    void foreignCurrencyTypesAreOnlyUsedAtTheApiBoundary() {
        noClasses().that().resideInAnyPackage("..logic..", "..data..", "..entities..")
                .and().resideOutsideOfPackage(CURRENCY_MODULE)
                .should().dependOnClassesThat().belongToAnyOf(CurrencyAmount.class, MoneyDto.class)
                .check(classes);
    }

    @Test
    void entitiesUseMoneyInsteadOfBigDecimal() {
        fields().that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
                .and().areDeclaredInClassesThat().resideOutsideOfPackage(CURRENCY_MODULE)
                // price of a booking: informative, in the currency it was booked in, never calculated with;
                // price of a reservation: in the currency of the offer, paid with PayPal as it is
                .and().areNotDeclaredIn(Booking.class)
                .and().areNotDeclaredIn(BookingReservation.class)
                .should().notHaveRawType(BigDecimal.class)
                .because("monetary values are persisted as Money, see Docs/money.md")
                .check(classes);
    }
}
