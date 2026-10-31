# Architecture

- **Ziel der App:** Nutzer hinterlegen Reiserouten und werden über Ereignisse informiert, die den Reiseverlauf beeinflussen (z. B. Straßensperrung). Fachliches Modell siehe [domain.md](domain.md).
- **Herkunft:** Das Repository war die App VacantAgent (Markt für LLM-Kapazität). Markt, Agents und LLM-Proxy sind entfernt (`V18`, `V19`). Auth, User, Infrastruktur und Deployment werden weiterverwendet. Abrechnung, PayPal und Provider-Onboarding sind Altbestand, siehe [Altbestand](#altbestand).
- **Technik:** Backend Spring Boot (Java 25, Maven) in `backend/`, App für Android und iOS mit React Native/Expo (TypeScript) in `frontend/`, siehe [App (Frontend)](#app-frontend), PostgreSQL, Betrieb als Docker Swarm Stack, siehe [deployment.md](deployment.md).

## System Context

```mermaid
flowchart LR
    user([Reisende]) -->|Android, iOS| fe[App]
    mcp([MCP-Clients<br>Claude, ChatGPT]) -->|/mcp| be
    fe -->|/api/v1| be[Backend]
    feed[(Verkehrsdaten-Feed)] -.->|Abruf, offen| be
    hook[(Drittanbieter)] -.->|Webhook, offen| be
    be -.->|Routing, offen| routing[(Routing-Dienst)]
    be -.->|Benachrichtigung, offen| notify[(Push / E-Mail)]
    be --> db[(PostgreSQL)]
    be -->|Kurse| ecb[(EZB)]
```

- **Reisende:** nutzen die App und melden sich über den eigenen Identity Provider an (OIDC im Systembrowser, durch Keycloak ersetzbar), siehe [authentication.md](authentication.md).
- **MCP-Clients:** greifen im Namen eines Nutzers über `/mcp` zu (Autorisierung per CIMD). Tools gibt es derzeit keine.
- **Verkehrsdatenquellen:** liefern Ereignisse in das Modul `input`, entweder abgerufen (`TRAFFIC_FEED`) oder gepusht (`WEBHOOK`). Noch keine Quelle angebunden.
- **Routing-Dienst:** soll aus den Stops eines Trips die Abschnitte einer Route mit erwarteter Durchfahrt berechnen. Noch nicht gewählt.
- **Benachrichtigung:** Kanal noch offen.

## Building Blocks

Jedes Modul ist ein Paket unter `com.vacantagent`.

| Modul | Aufgabe | Status |
|---|---|---|
| `trips` | Trips, Stops, Routen und ihre Abschnitte | neu |
| `network` | Streckennetz: Straßen und Segmente | neu |
| `bookings` | Buchungen an Stops | neu |
| `input` | Eingehende Ereignisse und ihre Quellen | neu |
| `incidents` | Incidents, Consequences, Zuordnungslogik | neu |
| `auth` | Eigener Identity Provider (OAuth2/OIDC, MCP-Clients per CIMD), unabhängig von allen anderen Modulen | Basis |
| `user` | `AppUser`, Absicherung von `/api/**` und `/mcp`, `CurrentUser` | Basis |
| `common` | `AbstractEntity`, Envers-Revision, Konverter, Fehlerbehandlung, OpenAPI | Basis |
| `currency` | Wechselkurse (EZB), Umrechnung an der API | Basis, wird nur von `accounting` gebraucht |
| `accounting`, `paypal`, `onboarding` | Konten, Ein- und Auszahlung über PayPal, Provider-Onboarding | Altbestand |

### Aufbau eines Moduls

- Nach dem Vorbild von `accounting`:
  - `entities`: JPA-Entities und Enums. Felder per Lombok `@Getter`, Änderungen nur über fachliche Methoden, geschützter Default-Konstruktor für JPA.
  - `data`: Spring-Data-Repositories, eigene Abfragen als JPQL.
  - `logic`: fachliche Services (`@Service`, `@Transactional`), Hilfskomponenten paketprivat.
  - `api` und `dto`: REST-Controller und DTOs (für die neuen Module noch nicht vorhanden).
  - `config`: `@Configuration` und `@ConfigurationProperties`.

### Abhängigkeiten zwischen Modulen

```mermaid
flowchart LR
    trips --> network
    bookings --> trips
    incidents --> network
    incidents -->|RouteSectionRepository| trips
    trips -.->|RouteChanged| incidents
    input --> incidents
    trips --> user
```

- **Referenzen über Modulgrenzen nur per Id** (`appUserId`, `segmentId`, `stopId`, `routeId`, `routeSectionId`, `incidentId`), mit Fremdschlüssel in der Datenbank. JPA-Beziehungen (`@ManyToOne`) nur innerhalb eines Moduls. Pfeile oben zeigen diese Id-Referenzen und die Aufrufe.
- **Rückwärts über Events:** `trips` kennt `incidents` nicht. Nach dem Planen einer Route veröffentlicht `RouteLogic` das Spring-Event `RouteChanged`, `incidents` reagiert darauf (synchron, in derselben Transaktion).
- **`incidents` liest den Index aus `trips`:** `ConsequenceMatcher` nutzt `RouteSectionRepository` (Index Segment → RouteSection). Später ggf. durch eine schmale Schnittstelle in `trips.logic` ersetzen.
- **`auth`** ist per ArchUnit (`AuthModuleIsolationTest`) isoliert. Für die neuen Module gibt es noch keine ArchUnit-Regeln.

## App (Frontend)

- **Ziel:** native App für Android und iOS, ersetzt das frühere Web-Frontend (React/Vite/antd, nginx). Kein Web-Frontend mehr, das Backend wird von der App direkt unter `BACKEND_URL` angesprochen.
- **Technik:** Expo SDK 58 (React Native 0.88, React 19), Expo Router (dateibasierte Routen in `frontend/src/app/`), TypeScript strict. Details und Befehle in [frontend/README.md](../frontend/README.md).
- **Entscheidungen:**
  - Expo statt „bare“ React Native: native Projekte werden aus `app.json` generiert (Continuous Native Generation, `android/`/`ios/` nicht eingecheckt), Cloud-Builds über EAS auch für iOS ohne Mac.
  - Development Build (`expo-dev-client`) statt Expo Go, weil der Login die eigene Redirect-URI `vacantagent://auth/callback` braucht.
  - Login per OIDC Authorization Code + PKCE im Systembrowser (`expo-auth-session`), Tokens im Secure Store, Refresh Tokens mit Rotation, siehe [authentication.md](authentication.md#app).
  - API-Client wie bisher aus der eingecheckten `frontend/openapi.json` generiert (`@hey-api/openapi-ts`, Fetch-Client).
  - Gestaltung über eigene Design-Tokens (`frontend/src/theme`: Schwarz/Weiß, Akzent Gelb `#f2b705`, Schrift Outfit) und wenige Basiskomponenten, keine UI-Bibliothek. Nur Light Mode. Vorläufig aus dem VacantAgent-Look übernommen.
  - Sprache der Oberfläche Deutsch.
- **Stand:** Login, Logout, Session über Neustarts, Startseite „Meine Reisen“ als Platzhalter mit Profilabruf (`GET /api/v1/users/me`) als API-Prüfung.

## Runtime View

1. **Ereignis kommt an** (offen): Quelle → `input` speichert ein `Event` unverändert (`payload`), doppelte Zustellung über `externalId` erkannt.
2. **Ereignis verarbeiten** (offen): Straße und Ort → Segmente, neuer oder bestehender `Incident` über `IncidentLogic.create`/`change`, `Event.processed(incidentId)`.
3. **Zuordnung:** `ConsequenceMatcher.matchIncident` findet über die Segmente die Routen, deren erwartete Durchfahrt die Gültigkeit überschneidet, und legt `Consequence`s an, aktualisiert oder entfernt sie.
4. **Route planen:** `RouteLogic.replaceSections` → `RouteChanged` → `ConsequenceMatcher.matchRoute` prüft gegen aktive Incidents.
5. **Benachrichtigen** (offen): neue oder geänderte Consequences an den Nutzer des Trips.

## Persistence

- **Schema nur per Flyway** (`backend/src/main/resources/db/migration`, Auth unter `db/migration/auth`), Hibernate validiert nur (`ddl-auto=validate`). Nächste freie Version: `V21`.
- **Datenbanken:** lokal und in Tests H2 (In-Memory bzw. Datei), im Stack PostgreSQL (Profil `postgres`). Migrationen daher in Standard-SQL, z. B. `SMALLINT` statt `TINYINT`, `VARCHAR(n)` statt `TEXT`.
- **Ids:** `BIGINT GENERATED BY DEFAULT AS IDENTITY` über `AbstractEntity`.
- **Zeiten:** `OffsetDateTime` / `TIMESTAMP(6) WITH TIME ZONE`, Dauern über `DurationConverter` als `BIGINT` (Nanosekunden).
- **Historie:** Hibernate Envers für `@Audited`-Entities, je eine `_aud`-Tabelle in derselben Migration, siehe [envers.md](envers.md).
- **Geometrie:** GeoJSON-Text, kein PostGIS, damit H2 und PostgreSQL gleich bleiben.

## Security

- `/api/**`: JWT des eigenen IdP (oder Keycloak), Daten immer nur des angemeldeten `AppUser` (`CurrentUser`).
- `/mcp`: eigene Tokens mit Audience der MCP-URI und Scopes `mcp:read`/`mcp:write`.
- Details in [authentication.md](authentication.md), Secrets in [deployment.md](deployment.md).

## Tests

- Integrationstests mit `@SpringBootTest` gegen H2 und alle Migrationen, z. B. `ConsequenceMatchingTest`. Die Migrationen sind zusätzlich gegen PostgreSQL 16 geprüft (manuell).
- Architekturregeln mit ArchUnit (`AuthModuleIsolationTest`, `MoneyArchitectureTest`, `PayPalModuleTest`).
- CI (`.github/workflows/backend.yml`): `mvn verify`, danach Abgleich der generierten `frontend/openapi.json`.
- App: Jest (`jest-expo`) und React Native Testing Library, CI (`.github/workflows/frontend.yml`): Typecheck, Tests, JS-Bundle für Android und iOS (`expo export`).

## Altbestand

- Aus VacantAgent stammen noch Code und Doku für Abrechnung ([accounting.md](accounting.md), [paypal.md](paypal.md), [money.md](money.md)), das Backend-Modul `onboarding`. Das Web-Frontend mit den Rollen Consumer, Provider und Betreiber und ihren Seiten ist mit der Umstellung auf die App entfernt. Die PayPal-Return-URLs (`vacantagent.frontend-url`) zeigen noch auf dessen Seiten.
- Ob davon etwas für die Reise-App gebraucht wird (z. B. bezahlte Benachrichtigungen), ist offen. Bis dahin bleibt der Code unverändert.

## Offene Punkte

- **Routing:** Dienst wählen (z. B. OSRM, GraphHopper, HERE), Abbildung seiner Strecke auf unsere Segmente, Neuberechnung bei Planänderung.
- **Streckennetz befüllen:** Quelle für Straßen, Anschlussstellen und Geometrie (z. B. OpenStreetMap, Autobahn GmbH), Aktualisierung.
- **Ereignisquellen:** konkrete Feeds und Webhooks, Abruf-Scheduler, Webhook-Endpunkt mit Signaturprüfung, Verarbeitung Event → Incident inkl. Ortsbestimmung.
- **Benachrichtigung:** Kanal (Web-Push, E-Mail, MCP), Auslöser (neue oder geänderte Consequence), Duplikate vermeiden.
- **Skalierung der Zuordnung:** derzeit synchron in der Transaktion. Bei vielen Routen oder Events asynchron nach dem Commit (`@TransactionalEventListener`, Queue).
- **Räumliche Abfragen:** falls nötig PostGIS, dann aber H2-Ersatz für Tests (z. B. Testcontainers).
- **API und App-Screens** für Trips, Stops, Buchungen, Incidents und Consequences, MCP-Tools.
- **App:** Push-Benachrichtigungen (z. B. `expo-notifications`), Karten, Store-Veröffentlichung und native Builds in CI (EAS), Icon und Splash.
- **ArchUnit-Regeln** für die neuen Module (nur Id-Referenzen, keine Zyklen).
- **Altbestand:** entscheiden, ob Abrechnung, PayPal und Onboarding bleiben oder entfernt werden; App-Name und Branding (README, Icon, Paketname `com.vacantagent`, App-Name, Bundle-Id `com.vacantagent.app` und Schema `vacantagent`).
