# Authentication

## Concept

- **OAuth2/OIDC with two separate roles** in the same backend process:
  - **Identity provider (IdP):** package `com.nfv.auth`. Accounts, login, registration, issuing signed JWTs (Spring Authorization Server).
  - **Resource server:** everything under `/api/**` and the MCP endpoint `/mcp`. Validates JWTs of the configured issuer and maps them to the `AppUser`.
- **The interface between the two is standards only:** OIDC discovery, JWKS, JWT claims. No Java calls, no shared entities or tables.
- **`AppUser`** is the application side of the account. The link to the token is the **username** (with the own IdP in the claim `sub`).

## Motivation for the module separation

- The own IdP is an interim solution. It is to be replaced later by an external IdP (e.g. Keycloak), **without changes to domain modules**.
- That only works if the domain backend knows nothing about the IdP except issuer URL and claim names – i.e. exactly what Keycloak provides as well.
- Conversely the IdP knows no domain data (`AppUser`, credit, …). What the application needs about a user is created on the resource server side.
- Secured by `AuthModuleIsolationTest` (ArchUnit): `auth` imports nothing from other `com.nfv` packages and is imported by none.

## Building blocks

| Location | Content |
|---|---|
| `auth/config/AuthSecurityConfig` | Filter chains for the protocol endpoints of the authorization server (`getEndpointsMatcher()`: `/oauth2/**`, AS metadata, OIDC discovery) and the pages (login, registration, consent, connections); token claims (`preferred_username`, `email`, `roles`, `aud`); signing key; token generator with refresh tokens for public clients; `PasswordEncoder` |
| `auth/config/CimdConfig`, `auth/cimd/*` | MCP clients via client ID metadata document, authorisations and consents via JDBC, see [MCP clients (CIMD)](#mcp-clients-cimd) |
| `auth/entities/AuthAccount` | Login account: `username` (unique), password hash, `email`, roles (default `user`; `tester` for `/api/v1/test/**`, see [traffic-feed.md](traffic-feed.md#test-source-manual)) |
| `auth/api`, `templates/auth` | `/login`, `/register`, `/oauth2/consent`, `/account/connections` (rendered on the server, as with Keycloak; German, in the app's noir look with the word mark "NoFrustration.Vacation."; shared layout with inline CSS in `templates/auth/layout.html`, hence no static resources behind the security filter chain) |
| `auth/config/StaleFormAccessDeniedHandler` | Login/registration form with outdated CSRF token (see [Outdated forms](#outdated-forms)) |
| `templates/error.html`, `messages*.properties` | Error page for browsers instead of the whitelabel page, German/English according to `Accept-Language` (otherwise English), link "Back to the app" to `nfv.frontend-url`; JSON clients still get the JSON error |
| `db/migration/auth/V3__auth_account.sql` | `auth_account`, `auth_account_role` – without foreign keys to domain tables |
| `db/migration/auth/V17__oauth2_authorization.sql` | `oauth2_authorization`, `oauth2_authorization_consent` (schema of Spring Authorization Server, `VARCHAR` instead of `BLOB`), `auth_cimd_client` (cache of the metadata documents) |
| `user/security/ApiSecurityConfig` | Filter chain `/api/**`: stateless, JWT validation via `issuer-uri`, tokens for `/mcp` are rejected |
| `user/security/McpSecurityConfig`, `McpScopeFilter`, `ProtectedResourceMetadataController` | Filter chain `/mcp`: audience, scopes per tool, `401`/`403` according to the MCP spec, `/.well-known/oauth-protected-resource/mcp` |
| `user/security/AppUserJwtAuthenticationConverter` | JWT → `AppUser` (create if necessary) → `AppUserAuthentication` with principal `CurrentUser(appUserId, username)` |
| `user/logic/AppUserProvisioning` | race-safe find-or-create |
| `common/data/UsernameRevisionListener` | writes the username to `revinfo.username` (Envers) |
| `auth/logic/TestAccountInitializer` | creates `test`/`test` with role `tester` at startup if no `AuthAccount` exists yet (`nfv.auth.test-account.enabled`); an existing `test` gets `tester` added at startup, but only if it is the oldest account (i.e. created by the initializer, not registered by someone else). The role takes effect in the frontend only after a new login (token) |

- Signing key: `nfv.auth.signing-key` (PEM, PKCS#8 RSA, secret `NFV_IDP_SIGNING_KEY`), `kid` is the JWK thumbprint. Without a key a temporary one is generated at startup (warning in the log), tokens then become invalid after a restart.
- Authorisations (codes, access and refresh tokens) and consents are stored in the database (`JdbcOAuth2AuthorizationService`).
- Client `nfv-frontend`: public, authorization code + PKCE + refresh token, redirect `http://localhost:18173/auth/callback`, no consent screen. Access token 15 min, refresh token 14 days, rotated on every use (`…client.nfv-frontend.token.*`). Refresh and revocation (`/oauth2/revoke`) only with `client_id`, as with the MCP clients.
- MCP clients: not pre-registered, but via their `client_id` URL, see below.

## Configuration

```properties
spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:18180
nfv.security.username-claim=sub      # Keycloak: preferred_username
nfv.security.roles-claim=roles       # Keycloak: realm_access.roles
spring.security.oauth2.authorizationserver.*  # own IdP only
nfv.auth.cors.allowed-origins=…       # own IdP only
nfv.auth.test-account.enabled=true    # own IdP only, false in production
nfv.auth.signing-key=…                # own IdP only, PEM
nfv.auth.allowed-resources=…          # own IdP only, default ${nfv.mcp.resource-uri}
nfv.auth.cimd.*                       # own IdP only, MCP clients, see below
nfv.mcp.resource-uri=${nfv.backend-url}/mcp
nfv.mcp.read-tools=…                  # no tools yet; all other tools need mcp:write
```

## Frontend

- `oidc-client-ts` + `react-oidc-context`, configuration in `frontend/src/auth/oidcConfig.ts` (`VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`, defaults: `http://localhost:18180`, `nfv-frontend`).
- `RequireAuth` protects the dashboard: not logged in → `signinRedirect` → login page of the IdP.
- `/auth/callback` (`AuthCallback`): code exchange by the `AuthProvider`, then back to the page where the login started (`state.returnTo`, own paths only).
- Tokens in `sessionStorage`. `frontend/src/auth/session.ts` keeps the login alive silently: `automaticSilentRenew` renews the access token shortly before expiry via refresh token, API requests renew beforehand if it is valid for less than 30 s, a 401 renews and repeats the request once. Network errors do not end the session.
- An expired access token no longer removes the user, the page stays mounted. Only when the IdP rejects the refresh token (expired, revoked) does `SessionExpiredDialog` appear over the page: login in a popup (page and inputs are kept), if the popup does not work, login in the same window with return to the same page (unsaved inputs are lost).
- `frontend/src/api/apiClient.ts`: generated API client with `Authorization: Bearer …`, relative URLs (`/api` is forwarded to the backend by the Vite dev server, `VITE_API_BASE_URL` overrides).
- Logout: revoke the refresh token, then `signoutRedirect` (OIDC logout of the IdP, back to `/`, from there back to the login).

## Workflows

### Registration

1. The frontend starts the login (`/oauth2/authorize`) → the IdP redirects to `/login`.
2. The user follows the link "Register" → `GET /register`.
3. `POST /register` validates the inputs and creates an `AuthAccount` with a bcrypt hash. Duplicate username: check beforehand, for simultaneous registration the unique constraint.
4. Redirect to `/login?registered`. **No `AppUser`** is created.

### Login (authorization code + PKCE)

1. Frontend → `GET /oauth2/authorize?response_type=code&client_id=nfv-frontend&code_challenge=…`.
2. Login via form. Then the IdP continues the saved request and redirects back to the frontend with `code`.
3. Frontend → `POST /oauth2/token` with `code` + `code_verifier` → access token (JWT, `sub` = username, `roles`, `email`) and ID token.
- Without a saved request (login page called directly), after login the user goes to the app (`nfv.frontend-url`), not to `/` of the backend.

### Outdated forms

- The CSRF token of the login form is stored in the session. It is outdated if
  - the form was submitted twice (double click, Enter + click): the first login replaces the token, the browser shows the response of the second,
  - the page was restored after login via the back button and submitted again,
  - the page was open longer than the session lives or the backend has been restarted in the meantime (deploy).
- Before: 403 with whitelabel page. Now (`StaleFormAccessDeniedHandler`, only `POST /login` and `POST /register`):
  - already logged in → redirect to the app, which gets its tokens without a new login,
  - not logged in → form again with a notice (`/login?expired`, `/register?expired`).
- Additionally a small script in the layout prevents submitting the same form a second time (enabled again after restoring from the back-forward cache).
- Other 403s of the pages (e.g. logout form with outdated token) show the error page.

### Authenticated API request

1. `Authorization: Bearer <jwt>` to `/api/**`.
2. Signature and issuer are checked. The keys come via discovery from the issuer, so also via HTTP with the own IdP.
3. The converter reads the username from the configured claim (missing → 401) and fetches or creates the `AppUser`.
4. The SecurityContext contains `CurrentUser(appUserId, username)`, roles as `ROLE_<role>`. Access via `@AuthenticationPrincipal CurrentUser user`.
5. **An authenticated request always has an existing `AppUser`.**

### First login: creating the AppUser

- Parallel first requests of the same user are the normal case (the SPA loads several resources).
- **No explicit locks:** `SELECT … FOR UPDATE` cannot lock a row that does not exist yet, JVM locks only work within one instance.
- Instead the **unique constraint on `app_user.username`** is the single source of truth:
  1. `findByUsername` → present, done.
  2. Otherwise insert in its own transaction (`REQUIRES_NEW`, `saveAndFlush`).
  3. `DataIntegrityViolationException` → a parallel request was faster → read again.
- Secured by `AppUserProvisioningTest` (16 parallel threads → exactly one `AppUser`).

### History tracking (Envers)

- `revinfo.username` contains the username of the authenticated user of a change, `NULL` for changes without user context.

## MCP clients (CIMD)

MCP clients such as Claude or ChatGPT connect according to the [MCP Authorization Specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization). They are not registered in advance: the `client_id` is the HTTPS URL of a **client ID metadata document** (CIMD), which the IdP fetches and checks. Concept and decisions: [CIMD concept: MCP authentication for Nfv](https://claude.ai/artifact/Nhx7NcpAaE12WVo4KcW9TG).

### Flow

1. Client → `POST /mcp` without token → `401` with `WWW-Authenticate: Bearer resource_metadata="<backend>/.well-known/oauth-protected-resource/mcp", scope="mcp:read"`.
2. The client reads the protected resource metadata (RFC 9728: `resource`, `authorization_servers`, `scopes_supported`) and the AS metadata `/.well-known/oauth-authorization-server` (`client_id_metadata_document_supported: true`, `token_endpoint_auth_methods_supported` contains `none`).
3. Client → `/oauth2/authorize?client_id=https://…&redirect_uri=…&code_challenge=…&resource=<backend>/mcp&scope=mcp:read mcp:write`.
4. The IdP resolves the `client_id` (`CimdClientResolver`): allowlist → cache → fetch → validation → `RegisteredClient` (public, PKCE required, consent required, `authorization_code` + `refresh_token`). Unknown or invalid clients: error page, **no** redirect.
5. `AuthorizationRequestValidator`: redirect URI from the document (loopback with any port), scopes, `resource` required and allowed (RFC 8707).
6. Login, then consent page `/oauth2/consent` (name and host of the client, target of the redirect, warning for local apps). The user can deselect `mcp:write`.
7. Token request with `code_verifier` → access token (15 min, `aud` = `<backend>/mcp`, `scope`, `client_id`) and refresh token (30 days, rotated on every use).
8. `/mcp` checks signature, issuer and `aud`; `McpScopeFilter` checks `mcp:read` for every request and `mcp:write` for `tools/call` except the `read-tools`. If a scope is missing: `403` with `error="insufficient_scope", scope="mcp:read mcp:write"`, the client asks the user again (step-up).
9. Tools only see data of the `AppUser` from the token (`CurrentUser`), as with the API.

### Rules

| Rule | Implementation |
|---|---|
| Known clients only | `nfv.auth.cimd.allowed-client-ids`: Claude (web/desktop/mobile), Claude Code, ChatGPT (prefix), VS Code. URLs not listed are never fetched. |
| `client_id` URL | `https`, port 443, with path, without query, fragment, user info and dot segments (`ClientMetadataValidator.validateClientId`) |
| Document | `client_id` equal to the URL, `client_name` (sanitised, max. 100 characters), `token_endpoint_auth_method` `none`, code flow only, 1–20 redirect URIs: `https`, loopback (`http://127.0.0.1`, `[::1]`, `localhost`) or private scheme of the own domain (`ai.claude:/…` for `claude.ai`) |
| SSRF protection | `HttpsClientMetadataSource`: all resolved addresses must be public (`AddressPolicy`), connection exactly to the checked IP (no DNS rebinding), TLS with host name verification, no redirects, no proxy, 3 s, 5 KB, max. 10 fetches per host and minute |
| Cache | `auth_cimd_client`, TTL from `Cache-Control: max-age`, limited to 5 min – 24 h, 1 h without a value. If the host is unreachable, the old document remains valid for 24 h. |
| Local apps read-only | Redirect URI not `https` (loopback, private scheme) → only `nfv.auth.cimd.native-scopes` (`mcp:read`). Reason: any program on the machine can open a loopback port and pose as the client, PKCE does not protect against that. Clients without an `https` redirect get exclusively `mcp:read`. |
| Audience | Token request with a different `resource` → `invalid_target`. Tokens with `aud` = MCP URI are rejected by `/api/**`, tokens without this `aud` are rejected by `/mcp`. |
| Refresh | `PublicClientRefreshAuthenticationConverter`/`-Provider` authenticate public clients with `client_id` only, `PublicClientRefreshTokenGenerator` gives them refresh tokens. Rotation: a used refresh token is invalid. |

### Connections

- `/account/connections` (login required): connected MCP clients of the user with scopes and last token issuance.
- "Revoke" deletes all authorisations (access and refresh tokens) and the consent of the client. Access tokens already issued remain valid until expiry (max. 15 min), since `/mcp` checks JWTs locally.

### Testing locally

- Automated: `McpAuthorizationFlowTest` plays through the complete flow with a stub for the metadata documents (discovery, consent, token, scopes per tool, refresh, loopback, revoke). Unit tests: `ClientMetadataValidatorTest`, `RedirectUrisTest`, `AddressPolicyTest`, `CimdClientResolverTest`.
- Manually with Claude Code (loopback, so read-only): start the backend, `claude mcp add --transport http nfv http://localhost:18180/mcp`, in Claude Code `/mcp` → Authenticate. For this leave `nfv.backend-url` unchanged (`http://localhost:18180`).
- Manually with Claude web/ChatGPT: the clients only reach public HTTPS URLs, so use a tunnel (e.g. `cloudflared tunnel --url http://localhost:18180`) and set `NFV_BACKEND_URL` to the tunnel URL, then add `https://<tunnel>/mcp` in Claude under "Connectors".
- MCP Inspector (`npx @modelcontextprotocol/inspector`) is not on the allowlist and is rejected.

## Switching to Keycloak

1. Create a realm + public client (PKCE), enable self-registration, **disable "Edit username"** (the mapping depends on the username).
2. Migrate accounts (bcrypt hashes can be imported), take over usernames unchanged.
3. Set `issuer-uri`, `username-claim=preferred_username`, `roles-claim=realm_access.roles`.
4. MCP: Keycloak must support CIMD and set `aud` to `nfv.mcp.resource-uri` (audience mapper), create the scopes `mcp:read`/`mcp:write`. `ProtectedResourceMetadataController` then reports the Keycloak issuer.
5. Remove: package `com.nfv.auth`, `templates/auth`, the properties `spring.security.oauth2.authorizationserver.*` and `nfv.auth.*`, the dependencies `spring-boot-starter-security-oauth2-authorization-server` and `spring-boot-starter-thymeleaf`.
6. Keep the migrations `V3__auth_account.sql` and `V17__oauth2_authorization.sql` in the repo (Flyway history) and drop the tables with a new migration.
7. Frontend: only change `authority` (issuer) and `client_id` of the OIDC configuration.

## Open

- The access token lives 5 minutes (default of the authorization server), then a redirect round trip via the IdP. Possibly increase `…token.access-token-time-to-live`.
- Production: the frontend (nginx) does not yet forward `/api` to the backend.
- Expired entries in `oauth2_authorization` are not cleaned up (job missing).
- Reuse of a rotated refresh token does not yet revoke the whole token family (only the old token is invalid).
- Link to `/account/connections` in the frontend is missing.
- `tools/list` shows all tools even with `mcp:read`, write calls are only rejected when called.
