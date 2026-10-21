# Authentifizierung

## Konzept

- **OAuth2/OIDC mit zwei getrennten Rollen** im selben Backend-Prozess:
  - **Identity Provider (IdP):** Paket `com.vacantagent.auth`. Accounts, Login, Registrierung, Ausstellen signierter JWTs (Spring Authorization Server).
  - **Resource Server:** alles unter `/api/**`. Validiert JWTs des konfigurierten Issuers und mappt sie auf den `AppUser`.
- **Schnittstelle zwischen beiden sind nur Standards:** OIDC Discovery, JWKS, JWT-Claims. Keine Java-Aufrufe, keine gemeinsamen Entities oder Tabellen.
- **`AppUser`** ist die Anwendungsseite des Accounts. Verbindendes Merkmal zum Token ist der **Username** (beim eigenen IdP im Claim `sub`).

## Motivation der Modultrennung

- Der eigene IdP ist eine Übergangslösung. Er soll später durch einen externen IdP (z. B. Keycloak) ersetzt werden, **ohne Änderungen an Fachmodulen**.
- Das geht nur, wenn das Fachbackend nichts über den IdP weiß außer Issuer-URL und Claim-Namen – also genau das, was auch Keycloak liefert.
- Der IdP kennt umgekehrt keine Fachdaten (`AppUser`, Guthaben, …). Was die Anwendung über einen Nutzer braucht, entsteht auf der Resource-Server-Seite.
- Abgesichert durch `AuthModuleIsolationTest` (ArchUnit): `auth` importiert nichts aus anderen `com.vacantagent`-Paketen und wird von keinem importiert.

## Bausteine

| Ort | Inhalt |
|---|---|
| `auth/config/AuthSecurityConfig` | Filterketten für OAuth2-Endpunkte (`/oauth2/**`, `/.well-known/**`) und Login-/Registrierungsseiten; Token-Claims (`preferred_username`, `email`, `roles`); `PasswordEncoder` |
| `auth/entities/AuthAccount` | Login-Account: `username` (unique), Passwort-Hash, `email`, Rollen (Default `user`) |
| `auth/api`, `templates/auth` | `/login`, `/register` (serverseitig gerendert, wie bei Keycloak) |
| `db/migration/auth/V3__auth_account.sql` | `auth_account`, `auth_account_role` – ohne Fremdschlüssel zu Fachtabellen |
| `user/security/ApiSecurityConfig` | Filterkette `/api/**`: stateless, JWT-Validierung über `issuer-uri` |
| `user/security/AppUserJwtAuthenticationConverter` | JWT → `AppUser` (anlegen falls nötig) → `AppUserAuthentication` mit Principal `CurrentUser(appUserId, username)` |
| `user/logic/AppUserProvisioning` | race-sicheres Find-or-Create |
| `common/data/UsernameRevisionListener` | schreibt den Username in `revinfo.username` (Envers) |
| `auth/logic/TestAccountInitializer` | legt beim Start `test`/`test` an, wenn noch kein `AuthAccount` existiert (`vacantagent.auth.test-account.enabled`) |

- Signierschlüssel: von Spring Boot beim Start generiert (RSA, nur im Speicher). Tokens werden durch einen Neustart ungültig.
- Client `vacantagent-frontend`: öffentlich, Authorization Code + PKCE, Redirect `http://localhost:5173/auth/callback`, kein Consent-Screen.

## Konfiguration

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080
vacantagent.security.username-claim=sub      # Keycloak: preferred_username
vacantagent.security.roles-claim=roles       # Keycloak: realm_access.roles
spring.security.oauth2.authorizationserver.*  # nur eigener IdP
vacantagent.auth.cors.allowed-origins=…       # nur eigener IdP
vacantagent.auth.test-account.enabled=true    # nur eigener IdP, in Produktion false
```

## Frontend

- `oidc-client-ts` + `react-oidc-context`, Konfiguration in `frontend/src/auth/oidcConfig.ts` (`VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`, Defaults: `http://localhost:8080`, `vacantagent-frontend`).
- `RequireAuth` schützt das Dashboard: nicht angemeldet → `signinRedirect` → Login-Seite des IdP.
- `/auth/callback` (`AuthCallback`): Code-Tausch durch den `AuthProvider`, danach Weiterleitung auf die Dashboard-Startseite `/`.
- Tokens im `sessionStorage`. Kein Refresh Token (öffentlicher Client): bei Ablauf des Access Tokens oder einer 401 der API wird der Nutzer entfernt und der Login neu gestartet; solange die IdP-Session besteht, ohne erneute Passworteingabe.
- `frontend/src/api/apiClient.ts`: generierter API-Client mit `Authorization: Bearer …`, relative URLs (`/api` wird vom Vite-Dev-Server an das Backend weitergeleitet, `VITE_API_BASE_URL` überschreibt).
- Abmelden: `signoutRedirect` (OIDC-Logout des IdP, zurück auf `/`, von dort wieder zum Login).

## Workflows

### Registrierung

1. Frontend startet den Login (`/oauth2/authorize`) → IdP leitet auf `/login` um.
2. Nutzer folgt dem Link „Create an account“ → `GET /register`.
3. `POST /register` validiert die Eingaben und legt einen `AuthAccount` mit bcrypt-Hash an. Doppelter Username: Prüfung vorab, bei gleichzeitiger Registrierung der Unique-Constraint.
4. Weiterleitung nach `/login?registered`. Es wird **kein `AppUser`** angelegt.

### Login (Authorization Code + PKCE)

1. Frontend → `GET /oauth2/authorize?response_type=code&client_id=vacantagent-frontend&code_challenge=…`.
2. Login per Formular. Danach setzt der IdP die gespeicherte Anfrage fort und leitet mit `code` zurück zum Frontend.
3. Frontend → `POST /oauth2/token` mit `code` + `code_verifier` → Access Token (JWT, `sub` = Username, `roles`, `email`) und ID Token.

### Authentifizierter API-Request

1. `Authorization: Bearer <jwt>` an `/api/**`.
2. Signatur und Issuer werden geprüft. Die Schlüssel kommen per Discovery vom Issuer, also auch beim eigenen IdP über HTTP.
3. Der Converter liest den Username aus dem konfigurierten Claim (fehlt er → 401) und holt bzw. erzeugt den `AppUser`.
4. Im SecurityContext liegt `CurrentUser(appUserId, username)`, Rollen als `ROLE_<rolle>`. Zugriff über `@AuthenticationPrincipal CurrentUser user`.
5. **Ein authentifizierter Request hat immer einen existierenden `AppUser`.**

### Erster Login: AppUser anlegen

- Parallele erste Requests desselben Nutzers sind der Normalfall (SPA lädt mehrere Ressourcen).
- **Keine expliziten Locks:** `SELECT … FOR UPDATE` kann eine noch nicht existierende Zeile nicht sperren, JVM-Locks wirken nur in einer Instanz.
- Stattdessen ist der **Unique-Constraint auf `app_user.username`** die einzige Wahrheit:
  1. `findByUsername` → vorhanden, fertig.
  2. Sonst Insert in eigener Transaktion (`REQUIRES_NEW`, `saveAndFlush`).
  3. `DataIntegrityViolationException` → ein paralleler Request war schneller → erneut lesen.
- Abgesichert durch `AppUserProvisioningTest` (16 parallele Threads → genau ein `AppUser`).

### Historisierung (Envers)

- `revinfo.username` enthält den Username des authentifizierten Nutzers einer Änderung, `NULL` bei Änderungen ohne Nutzerkontext.

## Wechsel zu Keycloak

1. Realm + öffentlichen Client (PKCE) anlegen, Self-Registration aktivieren, **„Edit username“ deaktivieren** (das Mapping hängt am Username).
2. Accounts migrieren (bcrypt-Hashes sind importierbar), Usernames unverändert übernehmen.
3. `issuer-uri`, `username-claim=preferred_username`, `roles-claim=realm_access.roles` setzen.
4. Entfernen: Paket `com.vacantagent.auth`, `templates/auth`, die Properties `spring.security.oauth2.authorizationserver.*` und `vacantagent.auth.*`, die Abhängigkeiten `spring-boot-starter-security-oauth2-authorization-server` und `spring-boot-starter-thymeleaf`.
5. Migration `V3__auth_account.sql` im Repo lassen (Flyway-Historie) und die Tabellen mit einer neuen Migration droppen.
6. Frontend: nur `authority` (Issuer) und `client_id` der OIDC-Konfiguration ändern.

## Offen

- Access Token lebt 5 Minuten (Default des Authorization Servers), danach Redirect-Runde über den IdP. Ggf. `…token.access-token-time-to-live` erhöhen.
- Produktion: Frontend (nginx) leitet `/api` noch nicht an das Backend weiter.
- Persistenter Signierschlüssel für Produktion (Keystore/Secret statt Generierung beim Start).
- Autorisierungen des IdP liegen im Speicher (`InMemoryOAuth2AuthorizationService`). Für mehrere Instanzen JDBC-Variante nutzen.
