# Authentifizierung

## Konzept

- **OAuth2/OIDC mit zwei getrennten Rollen** im selben Backend-Prozess:
  - **Identity Provider (IdP):** Paket `com.vacantagent.auth`. Accounts, Login, Registrierung, Ausstellen signierter JWTs (Spring Authorization Server).
  - **Resource Server:** alles unter `/api/**` und der MCP-Endpunkt `/mcp`. Validiert JWTs des konfigurierten Issuers und mappt sie auf den `AppUser`.
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
| `auth/config/AuthSecurityConfig` | Filterketten für die Protokoll-Endpunkte des Authorization Servers (`getEndpointsMatcher()`: `/oauth2/**`, AS-Metadaten, OIDC-Discovery) und die Seiten (Login, Registrierung, Consent, Verbindungen); Token-Claims (`preferred_username`, `email`, `roles`, `aud`); Signaturschlüssel; Token-Generator mit Refresh Tokens für öffentliche Clients; `PasswordEncoder` |
| `auth/config/CimdConfig`, `auth/cimd/*` | MCP-Clients per Client ID Metadata Document, Autorisierungen und Consents per JDBC, siehe [MCP-Clients (CIMD)](#mcp-clients-cimd) |
| `auth/entities/AuthAccount` | Login-Account: `username` (unique), Passwort-Hash, `email`, Rollen (Default `user`) |
| `auth/api`, `templates/auth` | `/login`, `/register`, `/oauth2/consent`, `/account/connections` (serverseitig gerendert, wie bei Keycloak) |
| `db/migration/auth/V3__auth_account.sql` | `auth_account`, `auth_account_role` – ohne Fremdschlüssel zu Fachtabellen |
| `db/migration/auth/V17__oauth2_authorization.sql` | `oauth2_authorization`, `oauth2_authorization_consent` (Schema von Spring Authorization Server, `VARCHAR` statt `BLOB`), `auth_cimd_client` (Cache der Metadata Documents) |
| `user/security/ApiSecurityConfig` | Filterkette `/api/**`: stateless, JWT-Validierung über `issuer-uri`, Tokens für `/mcp` werden abgelehnt |
| `user/security/McpSecurityConfig`, `McpScopeFilter`, `ProtectedResourceMetadataController` | Filterkette `/mcp`: Audience, Scopes pro Tool, `401`/`403` nach MCP-Spec, `/.well-known/oauth-protected-resource/mcp` |
| `user/security/AppUserJwtAuthenticationConverter` | JWT → `AppUser` (anlegen falls nötig) → `AppUserAuthentication` mit Principal `CurrentUser(appUserId, username)` |
| `user/logic/AppUserProvisioning` | race-sicheres Find-or-Create |
| `common/data/UsernameRevisionListener` | schreibt den Username in `revinfo.username` (Envers) |
| `auth/logic/TestAccountInitializer` | legt beim Start `test`/`test` an, wenn noch kein `AuthAccount` existiert (`vacantagent.auth.test-account.enabled`) |

- Signaturschlüssel: `vacantagent.auth.signing-key` (PEM, PKCS#8 RSA, Secret `VACANTAGENT_IDP_SIGNING_KEY`), `kid` ist der JWK-Thumbprint. Ohne Schlüssel wird beim Start ein temporärer erzeugt (Warnung im Log), Tokens werden dann durch einen Neustart ungültig.
- Autorisierungen (Codes, Access und Refresh Tokens) und Consents liegen in der Datenbank (`JdbcOAuth2AuthorizationService`).
- Client `vacantagent-app` (App in `frontend/`): öffentlich, Authorization Code + PKCE, Redirect `vacantagent://auth/callback` (privates URI-Schema nach RFC 8252), Logout-Redirect `vacantagent://auth/logout`, kein Consent-Screen. Access Token 15 Minuten, Refresh Token 30 Tage mit Rotation (`reuse-refresh-tokens=false`, `PublicClientRefresh*` wie bei MCP-Clients).
- Client `vacantagent-swagger`: Swagger UI des Backends, Authorization Code + PKCE, Redirect `${BACKEND_URL}/swagger-ui/oauth2-redirect.html`, kein Refresh Token.
- MCP-Clients: nicht vorregistriert, sondern über ihre `client_id`-URL, siehe unten.

## Konfiguration

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8080
vacantagent.security.username-claim=sub      # Keycloak: preferred_username
vacantagent.security.roles-claim=roles       # Keycloak: realm_access.roles
spring.security.oauth2.authorizationserver.*  # nur eigener IdP
vacantagent.auth.cors.allowed-origins=…       # nur eigener IdP
vacantagent.auth.test-account.enabled=true    # nur eigener IdP, in Produktion false
vacantagent.auth.signing-key=…                # nur eigener IdP, PEM
vacantagent.auth.allowed-resources=…          # nur eigener IdP, Default ${vacantagent.mcp.resource-uri}
vacantagent.auth.cimd.*                       # nur eigener IdP, MCP-Clients, siehe unten
vacantagent.mcp.resource-uri=${vacantagent.backend-url}/mcp
vacantagent.mcp.read-tools=…                  # noch keine Tools; alle anderen Tools brauchen mcp:write
```

## App

- `expo-auth-session` in `frontend/src/auth/oidc.ts`: Login im Systembrowser (iOS `ASWebAuthenticationSession`, Android Custom Tabs), die App sieht das Passwort nie. Konfiguration über `EXPO_PUBLIC_BACKEND_URL`, `EXPO_PUBLIC_OIDC_ISSUER` (Default: Backend-URL), `EXPO_PUBLIC_OIDC_CLIENT_ID` (Default `vacantagent-app`).
- Der Redirect auf `vacantagent://auth/callback` funktioniert nur im Development Build bzw. der gebauten App, nicht in Expo Go (eigenes Schema `exp://`).
- `Session` (`frontend/src/auth/session.ts`): Tokens im Secure Store (Keychain/Keystore), Login bleibt über App-Neustarts erhalten. Access Token wird 30 s vor Ablauf per Refresh Token erneuert, gleichzeitige Requests teilen sich einen Refresh (rotierte Refresh Tokens gelten nur einmal). Scheitert der Refresh, endet die Session.
- Routen: `src/app/(app)/**` nur mit Login (`Stack.Protected`), sonst `login`. `src/app/auth/[step].tsx` fängt die Redirect-Deep-Links ab.
- `frontend/src/api/apiClient.ts`: generierter API-Client mit `EXPO_PUBLIC_BACKEND_URL` als Basis und `Authorization: Bearer …`. Eine 401 der API beendet die Session, die App zeigt wieder den Login.
- Abmelden: Tokens löschen, danach OIDC-Logout des IdP im Browser (`id_token_hint`, zurück auf `vacantagent://auth/logout`), damit der nächste Login wieder nach dem Passwort fragt.

## Workflows

### Registrierung

1. App startet den Login (`/oauth2/authorize`) → IdP leitet auf `/login` um.
2. Nutzer folgt dem Link „Create an account“ → `GET /register`.
3. `POST /register` validiert die Eingaben und legt einen `AuthAccount` mit bcrypt-Hash an. Doppelter Username: Prüfung vorab, bei gleichzeitiger Registrierung der Unique-Constraint.
4. Weiterleitung nach `/login?registered`. Es wird **kein `AppUser`** angelegt.

### Login (Authorization Code + PKCE)

1. App → `GET /oauth2/authorize?response_type=code&client_id=vacantagent-app&redirect_uri=vacantagent://auth/callback&code_challenge=…` im Systembrowser.
2. Login per Formular. Danach setzt der IdP die gespeicherte Anfrage fort und leitet mit `code` auf `vacantagent://auth/callback` zurück, der Browser übergibt an die App.
3. App → `POST /oauth2/token` mit `code` + `code_verifier` → Access Token (JWT, `sub` = Username, `roles`, `email`), ID Token und Refresh Token.

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

## MCP-Clients (CIMD)

MCP-Clients wie Claude oder ChatGPT verbinden sich nach der [MCP Authorization Specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization). Sie werden nicht vorab registriert: Die `client_id` ist die HTTPS-URL eines **Client ID Metadata Documents** (CIMD), das der IdP abruft und prüft. Konzept und Entscheidungen: [CIMD-Konzept: MCP-Authentifizierung für VacantAgent](https://claude.ai/artifact/Nhx7NcpAaE12WVo4KcW9TG).

### Ablauf

1. Client → `POST /mcp` ohne Token → `401` mit `WWW-Authenticate: Bearer resource_metadata="<backend>/.well-known/oauth-protected-resource/mcp", scope="mcp:read"`.
2. Client liest die Protected Resource Metadata (RFC 9728: `resource`, `authorization_servers`, `scopes_supported`) und die AS-Metadaten `/.well-known/oauth-authorization-server` (`client_id_metadata_document_supported: true`, `token_endpoint_auth_methods_supported` enthält `none`).
3. Client → `/oauth2/authorize?client_id=https://…&redirect_uri=…&code_challenge=…&resource=<backend>/mcp&scope=mcp:read mcp:write`.
4. IdP löst die `client_id` auf (`CimdClientResolver`): Allowlist → Cache → Abruf → Validierung → `RegisteredClient` (öffentlich, PKCE Pflicht, Consent Pflicht, `authorization_code` + `refresh_token`). Unbekannte oder ungültige Clients: Fehlerseite, **keine** Weiterleitung.
5. `AuthorizationRequestValidator`: Redirect-URI aus dem Dokument (Loopback mit beliebigem Port), Scopes, `resource` Pflicht und erlaubt (RFC 8707).
6. Login, dann Consent-Seite `/oauth2/consent` (Name und Host des Clients, Ziel der Weiterleitung, Warnhinweis bei lokalen Apps). Der Nutzer kann `mcp:write` abwählen.
7. Token-Request mit `code_verifier` → Access Token (15 min, `aud` = `<backend>/mcp`, `scope`, `client_id`) und Refresh Token (30 Tage, bei jeder Nutzung rotiert).
8. `/mcp` prüft Signatur, Issuer und `aud`; `McpScopeFilter` prüft `mcp:read` für jeden Request und `mcp:write` für `tools/call` außer den `read-tools`. Fehlt ein Scope: `403` mit `error="insufficient_scope", scope="mcp:read mcp:write"`, der Client fragt den Nutzer erneut (Step-up).
9. Tools sehen nur Daten des `AppUser` aus dem Token (`CurrentUser`), wie bei der API.

### Regeln

| Regel | Umsetzung |
|---|---|
| Nur bekannte Clients | `vacantagent.auth.cimd.allowed-client-ids`: Claude (Web/Desktop/Mobile), Claude Code, ChatGPT (Präfix), VS Code. Nicht gelistete URLs werden nie abgerufen. |
| `client_id`-URL | `https`, Port 443, mit Pfad, ohne Query, Fragment, User-Info und Dot-Segmente (`ClientMetadataValidator.validateClientId`) |
| Dokument | `client_id` gleich URL, `client_name` (bereinigt, max. 100 Zeichen), `token_endpoint_auth_method` `none`, nur Code-Flow, 1–20 Redirect-URIs: `https`, Loopback (`http://127.0.0.1`, `[::1]`, `localhost`) oder privates Schema der eigenen Domain (`ai.claude:/…` für `claude.ai`) |
| SSRF-Schutz | `HttpsClientMetadataSource`: alle aufgelösten Adressen müssen öffentlich sein (`AddressPolicy`), Verbindung genau zur geprüften IP (kein DNS-Rebinding), TLS mit Hostname-Prüfung, keine Redirects, kein Proxy, 3 s, 5 KB, max. 10 Abrufe pro Host und Minute |
| Cache | `auth_cimd_client`, TTL aus `Cache-Control: max-age`, begrenzt auf 5 min – 24 h, ohne Angabe 1 h. Ist der Host nicht erreichbar, gilt das alte Dokument noch 24 h. |
| Lokale Apps nur lesend | Redirect-URI nicht `https` (Loopback, privates Schema) → nur `vacantagent.auth.cimd.native-scopes` (`mcp:read`). Grund: Jedes Programm auf dem Rechner kann einen Loopback-Port öffnen und sich als der Client ausgeben, PKCE schützt davor nicht. Clients ohne `https`-Redirect bekommen ausschließlich `mcp:read`. |
| Audience | Token-Request mit anderem `resource` → `invalid_target`. Tokens mit `aud` = MCP-URI lehnt `/api/**` ab, Tokens ohne diese `aud` lehnt `/mcp` ab. |
| Refresh | `PublicClientRefreshAuthenticationConverter`/`-Provider` authentifizieren öffentliche Clients nur mit `client_id`, `PublicClientRefreshTokenGenerator` gibt ihnen Refresh Tokens. Rotation: ein benutztes Refresh Token ist ungültig. |

### Verbindungen

- `/account/connections` (Login nötig): verbundene MCP-Clients des Nutzers mit Scopes und letzter Token-Ausgabe.
- „Revoke“ löscht alle Autorisierungen (Access und Refresh Tokens) und den Consent des Clients. Bereits ausgegebene Access Tokens bleiben bis zum Ablauf (max. 15 min) gültig, da `/mcp` JWTs lokal prüft.

### Lokal testen

- Automatisiert: `McpAuthorizationFlowTest` spielt den kompletten Ablauf mit einem Stub für die Metadata Documents durch (Discovery, Consent, Token, Scopes pro Tool, Refresh, Loopback, Revoke). Unit-Tests: `ClientMetadataValidatorTest`, `RedirectUrisTest`, `AddressPolicyTest`, `CimdClientResolverTest`.
- Manuell mit Claude Code (Loopback, also nur lesend): Backend starten, `claude mcp add --transport http vacantagent http://localhost:8080/mcp`, in Claude Code `/mcp` → Authentifizieren. Dafür `vacantagent.backend-url` unverändert lassen (`http://localhost:8080`).
- Manuell mit Claude Web/ChatGPT: Die Clients erreichen nur öffentliche HTTPS-URLs, also einen Tunnel nutzen (z. B. `cloudflared tunnel --url http://localhost:8080`) und `VACANTAGENT_BACKEND_URL` auf die Tunnel-URL setzen, dann in Claude unter „Connectors“ `https://<tunnel>/mcp` hinzufügen.
- MCP Inspector (`npx @modelcontextprotocol/inspector`) ist nicht auf der Allowlist und wird abgewiesen.

## Wechsel zu Keycloak

1. Realm + öffentlichen Client (PKCE) anlegen, Self-Registration aktivieren, **„Edit username“ deaktivieren** (das Mapping hängt am Username).
2. Accounts migrieren (bcrypt-Hashes sind importierbar), Usernames unverändert übernehmen.
3. `issuer-uri`, `username-claim=preferred_username`, `roles-claim=realm_access.roles` setzen.
4. MCP: Keycloak muss CIMD unterstützen und `aud` auf `vacantagent.mcp.resource-uri` setzen (Audience-Mapper), die Scopes `mcp:read`/`mcp:write` anlegen. `ProtectedResourceMetadataController` meldet dann den Keycloak-Issuer.
5. Entfernen: Paket `com.vacantagent.auth`, `templates/auth`, die Properties `spring.security.oauth2.authorizationserver.*` und `vacantagent.auth.*`, die Abhängigkeiten `spring-boot-starter-security-oauth2-authorization-server` und `spring-boot-starter-thymeleaf`.
6. Migrationen `V3__auth_account.sql` und `V17__oauth2_authorization.sql` im Repo lassen (Flyway-Historie) und die Tabellen mit einer neuen Migration droppen.
7. App: öffentlichen Client mit Redirect `vacantagent://auth/callback` und Refresh Tokens anlegen, `EXPO_PUBLIC_OIDC_ISSUER` und `EXPO_PUBLIC_OIDC_CLIENT_ID` setzen.

## Offen

- Abmelden in der App widerruft das Refresh Token nicht serverseitig (Revocation für öffentliche Clients fehlt), es verfällt erst nach 30 Tagen bzw. beim nächsten Refresh.
- Abgelaufene Einträge in `oauth2_authorization` werden nicht aufgeräumt (Job fehlt).
- Wiederverwendung eines rotierten Refresh Tokens widerruft noch nicht die ganze Token-Familie (nur das alte Token ist ungültig).
- Link auf `/account/connections` in der App fehlt.
- `tools/list` zeigt auch mit `mcp:read` alle Tools, schreibende Aufrufe werden erst beim Aufruf abgelehnt.
