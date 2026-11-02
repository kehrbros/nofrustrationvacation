package com.nfv.paypal;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The PayPal module is the technical connection only, business logic lives in the accounting module, see
 * Docs/paypal.md.
 */
class PayPalModuleTest {
    private static final String PAYPAL = "com.nfv.paypal..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nfv");

    @Test
    void payPalModuleKnowsNoBusinessModules() {
        noClasses().that().resideInAPackage(PAYPAL)
                .should().dependOnClassesThat(resideInAPackage("com.nfv..")
                        .and(resideOutsideOfPackages(PAYPAL, "com.nfv.common..")))
                .check(classes);
    }

    @Test
    void onlyTheAccountingModuleUsesPayPal() {
        noClasses().that().resideOutsideOfPackages(PAYPAL, "com.nfv.accounting..")
                .should().dependOnClassesThat().resideInAPackage(PAYPAL)
                .check(classes);
    }
}
