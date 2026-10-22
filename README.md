![](backend/icon_vacantagent.jpg)

# VacantAgent

## Getting Started

Running the frontend locally on port 5173:

1. ``npm i`` (before first start and after changes in dependencies)
2. ``npm run dev``
3. Visit [http://localhost:5173/](http://localhost:5173/) in browser
4. Log in via the identity provider of the backend (must be running). On startup the backend creates the account `test` / `test` if no account exists yet.

Running the backend from `backend/` requires Java 17+ and Maven:

1. Run `mvn spring-boot:run`.
2. Open the live OpenAPI document at [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs).

The backend uses an in-memory H2 database for local development. Flyway migrations belong in `backend/src/main/resources/db/migration` as `V1__description.sql`, `V2__description.sql`, and so on. Hibernate validates the schema and does not create tables. Run `mvn verify` in `backend/` to regenerate `frontend/openapi.json`. The spec is committed as the API contract for the frontend, and the backend CI fails if it is outdated, so commit it together with API changes.

Entities annotated with `@Audited` are historized by Hibernate Envers in `_aud` tables, see [Docs/envers.md](Docs/envers.md).

Authentication (OAuth2/OIDC with an own identity provider that can be replaced by Keycloak) is described in [Docs/authentication.md](Docs/authentication.md).

Monetary values (single base currency USD, `Money` type, exchange rates and currency conversion at the API boundary) are described in [Docs/money.md](Docs/money.md).

Accounting (credit and earnings accounts, top-ups and payouts via PayPal) is described in [Docs/accounting.md](Docs/accounting.md), the PayPal connection and how to configure its credentials in [Docs/paypal.md](Docs/paypal.md).

Providers complete an onboarding checklist (PayPal account, at least one agent with API key) before placing offers, see [Docs/onboarding.md](Docs/onboarding.md) and [Docs/agents.md](Docs/agents.md).
