# Onboarding

- **Consumer:** Registrierung, Rollenwahl (`/willkommen`), fertig.
- **Provider:** zwei Schritte, **Reihenfolge egal**:
  1. Registrieren (erledigt, sobald der Nutzer angemeldet ist)
  2. PayPal-Konto verknüpfen, siehe [accounting.md](accounting.md)
- **Backend:** `onboarding.logic.ProviderOnboarding` berechnet den Status aus `accounting`, `GET /api/v1/onboarding/provider` → `{registered, payoutAccountLinked, complete}`. Kein eigener Zustand: Trennen des PayPal-Kontos öffnet den Schritt wieder.

## Frontend

- Nach der Rollenwahl „Provider“ geht es direkt ins Provider-Dashboard.
- Solange das Onboarding offen ist, zeigt `DashboardLayout` rechts neben jeder Provider-Seite die **Onboarding-Checkliste** (`src/onboarding/OnboardingChecklist.tsx`, sticky, auf schmalen Bildschirmen darunter): Fortschritt `1/2`, erledigte Punkte abgehakt, offene Punkte gelb hinterlegt und klickbar:
  - PayPal verknüpfen → `/provider/auszahlungskonto`
- Status kommt aus `ProviderOnboardingContext`; Seiten, die einen Schritt ändern, rufen `reload()`.
- „Neues Angebot“ (`/provider/angebote/neu`) zeigt bis zum Abschluss statt des Formulars die offenen Schritte (`RequireProviderOnboarding`).
- PayPal-Rücksprung `/paypal/connected` liegt außerhalb des Dashboards. `AuthProvider` verarbeitet OIDC-Parameter nur auf `/auth/callback` (`skipSigninCallback`), weil auch PayPal mit `code` und `state` zurückkommt.
