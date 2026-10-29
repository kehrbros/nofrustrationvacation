package com.vacantagent.auth.api;

/**
 * Texts of the scopes shown on the consent and connections pages.
 */
final class Scopes {

    private Scopes() {
    }

    static Scope describe(String scope) {
        return switch (scope) {
            case "mcp:read" -> new Scope(scope, "Read your data");
            case "mcp:write" -> new Scope(scope, "Act on your behalf");
            case "openid", "profile", "email" -> new Scope(scope, "Your username and email address");
            default -> new Scope(scope, scope);
        };
    }

    record Scope(String value, String description) {
    }
}
