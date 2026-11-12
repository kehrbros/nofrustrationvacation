# CLAUDE.md

Travel incident app: users store travel routes and are informed about events that affect the course of their trip. The repository originates from Nfv (package name `com.nfv`).

## Binding requirements

Read and follow before every change to code, schema or docs:

- [Docs/architecture.md](Docs/architecture.md): modules, structure of a module, dependencies, persistence, security, tests, legacy code.
- [Docs/domain.md](Docs/domain.md): bounded contexts, entities, relationships, history tracking, open domain questions.
- [Docs/network.md](Docs/network.md): road network, GraphHopper, import, interfaces.
- [Docs/recommendations.md](Docs/recommendations.md): route suggestions during planning and for incidents (draft).
- [Docs/i18n.md](Docs/i18n.md): multilingual frontend (German and English), texts, plurals, formats, language selection.

These documents are binding. If a task deviates from them or is not compatible with them, do not deviate silently, ask instead. If a deviation is decided, update the affected document in the same change.

## Core rules (summary, details in the documents)

- **Module structure modelled on `bookings`:** `entities`, `data`, `logic`, `api`/`dto`, `config`. Entities with Lombok `@Getter`, changes only via domain methods, protected default constructor.
- **References across module boundaries only by id** with a foreign key in the database. `@ManyToOne` only within a module.
- **Respect the dependency direction**, no cycles. Backwards only via Spring events (e.g. `RouteChanged`).
- **`auth` stays isolated** (`AuthModuleIsolationTest`).
- **Schema only via Flyway**, standard SQL (`SMALLINT` instead of `TINYINT`). Database PostgreSQL with PostGIS (also in tests), PostGIS only for geometry, see [Docs/network.md](Docs/network.md). Never change existing migrations, create a new version.
- **Legacy code:** `accounting` and `onboarding` were removed (2026-11-12). `currency` stays unchanged until it has been decided whether it is needed, see [Docs/architecture.md](Docs/architecture.md#legacy-code). `paypal` stays and is adapted for payments of bookings, see [Docs/accommodation-alternatives.md](Docs/accommodation-alternatives.md#payment).
- **UI texts always bilingual:** every new or changed text in the frontend (including `aria-label`, placeholders, error messages) as a key in `frontend/src/i18n/de.ts` and `en.ts`, never hard-coded; format numbers, prices and dates via the language. Details in [Docs/i18n.md](Docs/i18n.md).
- **Validation feedback in forms:** required fields marked with `*`, errors at the field on leaving it and on submit, backend field errors (`field`) at the field too; always via `Field`/`useFieldErrors` (`frontend/src/components/form`), see [Docs/i18n.md](Docs/i18n.md#forms).
- **No shadows under buttons:** buttons and clickable chips (plus button, selection buttons, map chips) without `box-shadow`, outline only via border.
- **Docs** in `Docs/` in English as bullet points, keep them up to date with the code.
