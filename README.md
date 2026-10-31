![](backend/icon_vacantagent.jpg)

# VacantAgent

Travel incident app: users store their trip routes and are informed about events that affect them, e.g. road closures. The architecture is described in [Docs/architecture.md](Docs/architecture.md), the domain model in [Docs/domain.md](Docs/domain.md).

## Getting Started

The app in `frontend/` is a React Native app for Android and iOS built with Expo. Running it locally against a running backend (details in [frontend/README.md](frontend/README.md)):

1. ``npm install`` and ``npm run generate:api`` in `frontend/`
2. ``npm run android`` (Android emulator or device, needs the Android SDK; for the emulator run ``adb reverse tcp:8080 tcp:8080`` first) or ``npm run ios`` (macOS with Xcode). This builds and installs a development build; Expo Go is not supported because of the login redirect.
3. Log in via the identity provider of the backend. On startup the backend creates the account `test` / `test` if no account exists yet.

Running the backend from `backend/` requires Java 25 and Maven:

1. Run `mvn spring-boot:run`.
2. Open the live OpenAPI document at [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs).

The backend uses an in-memory H2 database for local development. Flyway migrations belong in `backend/src/main/resources/db/migration` as `V1__description.sql`, `V2__description.sql`, and so on. Hibernate validates the schema and does not create tables. Run `mvn verify` in `backend/` to regenerate `frontend/openapi.json`. The spec is committed as the API contract for the app, and the backend CI fails if it is outdated, so commit it together with API changes.

Entities annotated with `@Audited` are historized by Hibernate Envers in `_aud` tables, see [Docs/envers.md](Docs/envers.md).

Authentication (OAuth2/OIDC with an own identity provider that can be replaced by Keycloak) is described in [Docs/authentication.md](Docs/authentication.md).

Monetary values (single base currency USD, `Money` type, exchange rates and currency conversion at the API boundary) are described in [Docs/money.md](Docs/money.md).

Accounting (credit and earnings accounts, top-ups and payouts via PayPal, carried over from the former VacantAgent app) is described in [Docs/accounting.md](Docs/accounting.md), the PayPal connection and how to configure its credentials in [Docs/paypal.md](Docs/paypal.md).

Running PostgreSQL and the backend as a Docker Swarm stack (`docker-compose.yml`, profile `postgres`, credentials as Docker secrets) is described in [Docs/deployment.md](Docs/deployment.md). For a local run with PostgreSQL that builds all images from source, copy `.env.example` to `.env` and run `docker compose -f docker-compose.local.yml up --build`.
