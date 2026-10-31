# CLAUDE.md

Reise-Incident-App: Nutzer hinterlegen Reiserouten und werden über Ereignisse informiert, die den Reiseverlauf beeinflussen. Das Repository stammt aus VacantAgent (Paketname `com.vacantagent`).

## Verbindliche Vorgaben

Vor jeder Änderung an Code, Schema oder Doku lesen und einhalten:

- [Docs/architecture.md](Docs/architecture.md): Module, Aufbau eines Moduls, Abhängigkeiten, Persistenz, Security, Tests, Altbestand.
- [Docs/domain.md](Docs/domain.md): Bounded Contexts, Entitäten, Beziehungen, Historisierung, offene fachliche Punkte.
- [Docs/network.md](Docs/network.md): Streckennetz, GraphHopper, Import, Schnittstellen.

Diese Dokumente sind verbindlich. Weicht eine Aufgabe davon ab oder ist sie damit nicht vereinbar, nicht stillschweigend abweichen, sondern nachfragen. Wird eine Abweichung beschlossen, das betroffene Dokument in derselben Änderung anpassen.

## Kernregeln (Kurzfassung, Details in den Dokumenten)

- **Modulaufbau nach Vorbild `accounting`:** `entities`, `data`, `logic`, `api`/`dto`, `config`. Entities mit Lombok `@Getter`, Änderungen nur über fachliche Methoden, geschützter Default-Konstruktor.
- **Referenzen über Modulgrenzen nur per Id** mit Fremdschlüssel in der Datenbank. `@ManyToOne` nur innerhalb eines Moduls.
- **Abhängigkeitsrichtung einhalten**, keine Zyklen. Rückwärts nur über Spring-Events (z. B. `RouteChanged`).
- **`auth` bleibt isoliert** (`AuthModuleIsolationTest`).
- **Schema nur per Flyway**, Standard-SQL (`SMALLINT` statt `TINYINT`). Datenbank PostgreSQL mit PostGIS (auch in Tests), PostGIS nur für Geometrie, siehe [Docs/network.md](Docs/network.md). Bestehende Migrationen nie ändern, neue Version anlegen.
- **Altbestand** (`accounting`, `paypal`, `onboarding`) nicht ändern, solange nicht entschieden ist, was davon bleibt.
- **Doku** in `Docs/` auf Deutsch in Stichpunkten, mit dem Code aktuell halten.
