package com.vacantagent.auth.api;

/**
 * Texts of the scopes shown on the consent and connections pages.
 */
final class Scopes {

    private Scopes() {
    }

    static Scope describe(String scope) {
        return switch (scope) {
            case "mcp:read" -> new Scope(scope, "Deine Daten lesen");
            case "mcp:write" -> new Scope(scope, "In deinem Namen handeln");
            case "openid", "profile", "email" -> new Scope(scope, "Dein Benutzername und deine E-Mail-Adresse");
            default -> new Scope(scope, scope);
        };
    }

    record Scope(String value, String description) {
    }
}
