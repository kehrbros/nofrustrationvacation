package com.vacantagent.auth;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The auth module must stay replaceable by an external identity provider, see Docs/authentication.md.
 */
class AuthModuleIsolationTest {
    private static final String AUTH = "com.vacantagent.auth..";

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.vacantagent");

    @Test
    void authModuleDoesNotDependOnOtherModules() {
        noClasses().that().resideInAPackage(AUTH)
                .should().dependOnClassesThat(resideInAPackage("com.vacantagent..").and(resideOutsideOfPackage(AUTH)))
                .check(classes);
    }

    @Test
    void otherModulesDoNotDependOnAuthModule() {
        noClasses().that().resideOutsideOfPackage(AUTH)
                .should().dependOnClassesThat().resideInAPackage(AUTH)
                .check(classes);
    }
}
