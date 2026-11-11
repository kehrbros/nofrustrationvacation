# Pläne bei Incidents (Zielbild)

- **Status:** Zielbild, beschlossen 2026-11-11 (Philipp), nicht umgesetzt.
- **Ziel:** Bei einem Incident schlägt der Agent ganze Pläne vor, nicht nur andere Routen. Ein Plan bündelt mehrere Schritte (Umfahrung, Route teilen, Übernachtung unterwegs, Ziel-Unterkunft umbuchen). Der Nutzer wählt einen Plan und führt ihn mit einem Klick aus, einschließlich aller Buchungen.
- **Priorität:** rechtzeitig ankommen. Geht das nicht, das Ziel später erreichen. Geht auch das nicht, ein Ersatzziel.
- Baut auf [advisor.md](advisor.md), [recommendations.md](recommendations.md), [accommodation-alternatives.md](accommodation-alternatives.md) auf.

## Beispiel

- Kaikōura → Wānaka bis auf Weiteres gesperrt, Unterkunft in Wānaka gebucht.
- Plan: heute bis Geraldine, dort Hotel, morgen über die Umfahrung nach Wānaka, erste Nacht in Wānaka absagen (Buchung behalten, Unterkunft informieren). Ein Knopf: „Plan übernehmen und 128 NZD mit PayPal zahlen“.

## Rollen

- **Modell baut Pläne** aus Bausteinen über das Tool `compose_plan`, vergleicht und begründet.
- **Backend rechnet und prüft** jeden Plan: Zeitleiste über alle Tage, Incidents zur jeweiligen Durchfahrt, Ankunft, Frist, Kosten, Wirkung auf Folgestops. Das Modell erfindet keine Zahlen.
- **Nutzer bestätigt.** Kein Tool ändert die Reise oder bucht.

## Bausteine und `compose_plan`

- **Bausteine** kommen wie heute aus den Tools des Laufs: `route_<n>`, `departure_<n>`, `keep_route`, `area_<n>`, `stay_<n>`, dazu neu `place_<n>` (Ersatzziel, siehe unten).
- **`compose_plan`** nimmt eine geordnete Liste von Schritten und liefert `plan_<n>` mit dem Ergebnis des Backends:
  - Schritte: Teilstrecke (Route oder Abfahrt), Übernachtung (`area_<n>` + `stay_<n>`), Ziel-Unterkunft (behalten und informieren / ersetzen mit `stay_<n>`), Ersatzziel (`place_<n>` + `stay_<n>`).
  - Ergebnis: Ankunft am Ziel, Verspätung gegen die Frist, `reaches_target`, `meets_deadline`, zusätzliche Nächte, Mehrkosten, Weiterfahrt geprüft, betroffene Folgestops, Probleme.
  - Unstimmige Pläne (Weiterfahrt gesperrt, Ankunft nach 22:00, Hotel nicht am Weg) liefern ein `problem`, das Modell kann nachbessern.
- **`submit_proposals`** nennt nur noch `plan_<n>` (ein einzelner Baustein ist ein Plan mit einem Schritt).
- **Mehr Runden:** `max-turns` erhöhen (Richtwert 16) und mehrere Tool-Aufrufe je Runde erlauben (`parallel_tool_calls`), sonst reicht es nicht für 2–3 Pläne.

## Regeln

- **Leiter** (Hinweis an das Modell und Grundlage ohne Modell):
  1. rechtzeitig und kostenlos (Umfahrung, andere Abfahrt)
  2. verspätet, aber im Check-in-Fenster: weiterfahren, Unterkunft informieren
  3. Ziel heute erreichbar, aber nach dem Check-in: andere Unterkunft am Ziel
  4. Ziel heute nicht erreichbar: Übernachtung unterwegs + geprüfte Weiterfahrt
  5. Ziel nicht erreichbar: Ersatzziel
- **Frist** ist der späteste Check-in der Unterkunft (`checkInUntil`), sonst `Stop.arrivalAt`.
- **Fahrtag:** Ankunft spätestens 22:00 (`stopover.latest-arrival`), Weiterfahrt frühestens zum Ende der Sperrung, sonst 09:00.
- **Weiterfahrt:** Eine Übernachtung zählt nur, wenn die Strecke danach geprüft frei oder umfahrbar ist.
- **Ziel-Unterkunft:** Geht nur die erste Nacht verloren, bleibt die Buchung und die Unterkunft wird informiert (To-do `NOTIFY_LATE_ARRIVAL`). Gehen alle Nächte verloren, wird neu gebucht und die alte kommt auf die To-do-Liste (`CANCEL`). Nie automatisch stornieren.
- **Ersatzziel:** Ein Ort, der dem geplanten Urlaubserlebnis möglichst nahekommt und die restliche Reise später möglichst noch zulässt (z. B. nah an den Folgestops, ähnliche Art von Ort). Der Plan verlegt den Ziel-Stop dorthin. Hat der Nutzer andere Vorstellungen, plant er selbst um.
- **Kosten:** kostenlos vor kostenpflichtig, dann frühere Ankunft, dann weniger Änderungen an der Reise. Mehrkosten über bisheriger Preis + 25 % nur als Alternative.
- **Auswahl:** 1 empfohlener Plan, höchstens 2 weitere unterschiedlicher Art.

## Datenmodell

- `AdvisorProposal` ist der Plan (Rang, Begründung, Ergebnis des Backends).
- Neue Tabelle `advisor_proposal_step` (Migration `V50`): Plan, Position, Art (`ROUTE`, `ADD_STOP`, `BOOK_STAY`, `REPLACE_STAY`, `NOTIFY_STAY`, `MOVE_TARGET`, `SHIFT_STOP`), Werte des Schritts (Punkte, Abfahrt, Stop, Angebot, `replacesBookingId`).
- Ausführung mit Status je Schritt (`PENDING`, `DONE`, `FAILED`), Reservierungen des Plans zusammengefasst, fortsetzbar im Hintergrund wie `ReservationRecovery`.

## Ausführen mit einem Klick

- **Eine PayPal-Freigabe** für den ganzen Plan: eine Order, eine `purchase_unit` je Hotel, gesamt autorisieren.
- **Erst buchen, dann die Reise ändern:** je Hotel bei LiteAPI buchen und capturen. Scheitert eins, werden die übrigen Autorisierungen freigegeben und schon gebuchte storniert und erstattet, die Reise bleibt unverändert. Erst wenn alle Buchungen stehen, ändert eine Transaktion Stops und Routen.
- **Fortschritt** je Schritt im Sheet („Hotel Geraldine bestätigt“, „Route angepasst“).

## Heute vorhanden

- Umfahrung, Abfahrt, Orte vor dem Hindernis, Hotelsuche am Ort und am Ziel, `STOPOVER`/`TARGET_STAY` als einzelne Vorschläge.
- PayPal autorisieren → LiteAPI → Capture, Hintergrund-Job, Storno mit Erstattung, To-do-Liste.
- **Fehlt:** Plan aus mehreren Schritten, Prüfung der Weiterfahrt, `checkInUntil` als Frist, Nachricht an die Unterkunft (`LateArrivalNotices`), Ersatzziel, gemeinsame Zahlung, Ausführung in der Reihenfolge oben. Heute löscht das Übernehmen eines `STOPOVER` den Lauf und übernimmt die Umfahrung nicht.

## Ausbaustufe

- **Feedback zum Plan:** Der Nutzer sagt, was nicht passt („lieber an der Küste bleiben“), der Agent überarbeitet den Plan mit dieser Vorgabe.
