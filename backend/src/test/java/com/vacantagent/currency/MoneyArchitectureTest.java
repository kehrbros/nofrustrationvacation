package com.vacantagent.currency;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.vacantagent.common.money.CurrencyAmount;
import com.vacantagent.currency.dto.MoneyDto;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The backend calculates in the base currency only, other currencies exist at the API boundary, see Docs/money.md.
 */
class MoneyArchitectureTest {
    private static final String CURRENCY_MODULE = "com.vacantagent.currency..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.vacantagent");

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
                .should().notHaveRawType(BigDecimal.class)
                .because("monetary values are persisted as Money, see Docs/money.md")
                .check(classes);
    }
}
