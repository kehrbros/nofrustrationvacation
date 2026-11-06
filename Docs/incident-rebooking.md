# Konzept: Vom Reiseplaner zur Umplanungs-App

- **Status:** Entwurf (2026-11-05). Umgesetzt 2026-11-06: PayPal stabil (Button im Sheet, Abschluss im Hintergrund, Sonderfälle, Storno mit Erstattung), PayPal-Mehrwert in der Oberfläche, Teile der UX-Überarbeitung. Bei der LiteAPI-Sandbox geblieben (Philipp: keine Demo-Hotels). Der Plan nach einem Incident wird separat umgesetzt.
- **Ziel:** Die App wird zur Hilfe für den Moment, in dem eine Reise schiefgeht. Ein Incident trifft die Route oder eine Unterkunft fällt aus, die App hat schon einen fertigen Plan B, der Nutzer bestätigt ihn mit einem Tipp und zahlt mit PayPal.
- **Reiseplanung bleibt Mittel zum Zweck:** Sie liefert Stops, Routen und Buchungen, damit die App die Lage bewerten kann. Sie soll schnell gehen und rückt in der Oberfläche nach hinten.
- **KI bleibt unsichtbar:** Der Nutzer sieht Empfehlungen und Begründungen in Klartext, aber keine Hinweise auf einen Assistenten. Harte Fakten wie Preis, Verfügbarkeit und Erreichbarkeit prüft weiter das Backend. Das Modell wählt nur aus und begründet ([accommodation-alternatives.md](accommodation-alternatives.md#rollen-assistant-und-litepi)).
- Baut auf dem Bestehenden auf: Bewertung der Consequences ([consequence-assessment.md](consequence-assessment.md)), Streckenvorschläge ([recommendations.md](recommendations.md)), Suche und Buchung alternativer Unterkünfte ([accommodation-alternatives.md](accommodation-alternatives.md)), Consequences auf der Karte und `TripAlternativesSheet`.

## Kernversprechen

> „Auf deiner Strecke ist etwas passiert. Wir haben schon eine Lösung, tippe auf Bestätigen.“

| Schritt | heute | Ziel |
|---|---|---|
| Erkennen | Verkehrsfeed → Incident → Consequence mit Kategorie (`TOO_LATE`, `DELAYED`, `NO_IMPACT`) | bleibt |
| Informieren | nur sichtbar, wenn der Nutzer die Reise öffnet | Push-Benachrichtigung mit Lage und Lösung in einem Satz, Banner auf der Reise |
| Lösung finden | Dialog mit zwei Wegen (Route, Unterkunft), jeder öffnet ein eigenes Sheet mit Formular | **ein empfohlener Plan** je Consequence, fertig berechnet, Alternativen eingeklappt darunter |
| Entscheiden | Kriterien prüfen, suchen, Treffer wählen, Gastdaten eintippen | ein Tipp auf „Umbuchen für 142 NZD“, alles vorbelegt, Anpassen nur auf Wunsch |
| Bezahlen | Weiterleitung zu PayPal und zurück | PayPal-Button im Sheet (Popup), später mit gespeichertem PayPal ohne Weiterleitung |
| Abschließen | neue Buchung angelegt, alte nur auf Wunsch als storniert markiert | Bestätigung mit Wegbeschreibung, alte Buchung markiert, Link zum Stornieren beim alten Anbieter, Nachricht an die alte Unterkunft vorformuliert |

## Zwei Einstiege, ein Ablauf

1. **Incident auf der Route** (automatisch): Consequence `TOO_LATE` oder `DELAYED` ab einer Schwelle (z. B. 30 min). Push und Banner öffnen den Plan.
2. **„Problem mit meiner Unterkunft“** (vom Nutzer, vor Ort): großer Knopf am aktuellen Stop, sobald der Check-in-Tag erreicht ist. Gründe als Chips („Keine Buchung gefunden“, „Überbucht“, „Komme nicht hin“), kein Formular. Danach derselbe Plan-Bildschirm.

Beide enden im selben **Plan-Sheet**. Es ersetzt `TripAlternativesSheet` als Ziel und bündelt `RouteSheet` und `AlternativesSheet`. Die beiden bleiben als Detailansicht hinter „Andere Möglichkeiten“.

## Der Plan (Backend)

- **Neu:** `GET /api/v1/consequences/{routeId}/{incidentId}/plan` und `POST /api/v1/stops/{stopId}/accommodation-problem` (Einstieg 2). Beide liefern einen `RescuePlan`, berechnet bei jedem Aufruf und nie gespeichert (Prinzip [route-choice](recommendations.md): gespeichert wird nur die Wahl).
- `RescuePlan`: `situation` (Kategorie, Verspätung an der Frist, Klartext), `recommended` (eine Option), `others` (bis zu drei), `computedAt`.
- **Option-Typen** und wann sie gebaut werden (Regeln deterministisch, in dieser Reihenfolge):

| Lage | Option | Quelle |
|---|---|---|
| Umfahrung erreicht die Frist | `REROUTE`: andere Strecke, kostenlos | `Suggestions.forConsequence` (Entwurf in recommendations.md, noch offen) |
| Sperrung mit Ende, spätere/frühere Abfahrt reicht | `SHIFT_DEPARTURE` | `DepartureShiftGenerator` (offen) |
| Ziel nicht rechtzeitig erreichbar, Unterkunft am Ziel gebucht | `STAY_BEFORE`: Unterkunft vor der Sperrung, an der Route, erreichbar vor dem spätesten Check-in | LiteAPI-Suche mit Region = Punkt auf der Route vor der betroffenen Section (neuer Use-Case „anderer Ort“) |
| Unterkunft nicht nutzbar (Einstieg 2) | `STAY_NEARBY`: gleichwertige Unterkunft in der Nähe | `AlternativeSearch` mit vorbefüllten Kriterien, unverändert |
| Verspätung, Frist hält aber knapp | `NOTIFY_HOST`: Nachricht „komme ca. 21:30“ an die Unterkunft, vorformuliert | Kontakt aus `AccommodationDetails` |
| immer | `KEEP`: „So weiterfahren (+1 h 10 min)“ | `KEEP_ROUTE` |

- **Auswahl der Empfehlung:** kostenlos vor kostenpflichtig, sofern die Frist gehalten wird. Sonst die günstigste Unterkunft, die alle Kriterien erfüllt, in der Reihenfolge des Rankings. Das Modell schreibt nur den Begründungssatz („Gleiche Kategorie, 18 km vor der Sperrung, Check-in bis 23 Uhr“).
- **Kriterien ohne Formular:** Max. Preis = bisheriger Preis + Aufschlag (Vorschlag +25 %), Gäste/Zimmer aus der Buchung bzw. `Trip.travelers`, Zeitraum aus der Buchung. Der Nutzer sieht die Werte erst unter „Anpassen“.
- **Gastdaten** (Name, E-Mail, Telefon) einmal im Profil hinterlegen, beim ersten Umbuchen abfragen und merken. Heute startet `AlternativesSheet` mit leeren Gastdaten.
- **Zeitbudget:** Der Plan muss in wenigen Sekunden stehen. LiteAPI und Routing parallel abfragen, das Ranking mit Timeout (heute fällt es ohne Assistant schon auf Preis/Abstand zurück).
- **Vorberechnen für die Push:** Beim Anlegen einer Consequence `TOO_LATE` rechnet ein Listener den Plan einmal aus, nur um den Text der Push zu bauen („Umfahrung über SH 7, +20 min“). Beim Öffnen wird neu gerechnet.

## PayPal stabil machen

### Was heute instabil ist

Aus dem Code abgeleitet. Gegen die Sandbox ist der Ablauf noch nie gelaufen.

1. **Vollständige Weiterleitung zu PayPal in einer installierten PWA.** Der Button ist ein Link auf `approveUrl` (`AlternativesSheet.tsx`). In der installierten App (`display: standalone`) öffnet sich PayPal als fremde Seite in einem Browser-Fenster außerhalb der App, die Rückkehr-URL landet dort, nicht in der App. Die Anmeldung liegt in `sessionStorage` (`oidcConfig.ts`), dort gibt es sie nicht. Folge: erneuter Login, und in der App selbst hängt der Ablauf beim Schritt „Bezahlen“. Das ist der wahrscheinlichste Grund für Abbrüche in der Demo am Handy.
2. **Rückkehr-URL hängt an `nfv.frontend-url`.** Lokal braucht es ngrok, die URL ändert sich bei jedem Tunnelstart. Login-Redirect, CORS und PayPal-Rückkehr müssen dann zusammen umgestellt werden.
3. **Abschluss nur, wenn der Nutzer zurückkommt.** Autorisieren, Buchen bei LiteAPI und Capture laufen synchron in `POST /booking-reservations/{id}/booking`. Schließt der Nutzer die Seite nach der Freigabe bei PayPal, bleibt die Reservierung in `PAYMENT_PENDING` oder `AUTHORIZED` stehen. Ein Abgleich im Hintergrund fehlt (Doku: „Kein Poller“).
4. **Sonderfälle enden im Log.** LiteAPI `4005` (schon gebucht, Antwort verloren) und ein abgelehnter Capture nach erfolgter Buchung werden nur geloggt. Die Reservierung bleibt stehen, der Nutzer sieht einen Fehler, obwohl das Zimmer gebucht sein kann.
5. **Zwei externe Sandboxes hintereinander.** LiteAPI-Sandbox (`ACC_CREDIT_CARD` mit versteckter Testkarte) und PayPal-Sandbox müssen beide gleichzeitig funktionieren. Fällt eine aus oder ändert Antworten, scheitert die Vorführung, und PayPal sieht schuldig aus.
6. **Keine Webhooks.** Der Zustand bei PayPal wird nur aktiv gelesen.
7. **Nie Ende-zu-Ende getestet.** Die Tests mocken PayPal und LiteAPI. Feldnamen und Status (z. B. `payer-action`-Link, `firstAuthorization`) sind nur gegen die Doku gebaut.

### Maßnahmen, in dieser Reihenfolge

1. **PayPal JS SDK statt Weiterleitung (Smart Buttons).** Der PayPal-Button wird im Plan-Sheet gerendert, `createOrder` ruft unser `POST …/alternative-reservations` (gibt die Order-Id zurück), `onApprove` ruft `POST /booking-reservations/{id}/booking`. Der Nutzer bleibt in der App, ohne Rückkehr-URL, ohne ngrok und ohne Verlust des Logins. Die Weiterleitung bleibt als Fallback. Dafür: Script von `www.paypal.com/sdk/js` (Client-Id ist öffentlich), CSP und Service Worker (nicht cachen) anpassen, `intent=authorize` im Script-Parameter.
2. **Resumable im Hintergrund.** Ein `@Scheduled`-Job (jede Minute) führt jede Reservierung in `PAYMENT_PENDING` (Order-Status bei PayPal `APPROVED`), `AUTHORIZED` oder `PROVIDER_BOOKED` mit derselben Logik wie `complete` weiter. Abgelaufene oder abgebrochene (`PAYMENT_PENDING` > 3 h) gehen auf `FAILED`. Das Frontend fragt beim Öffnen der Reise offene Reservierungen ab und zeigt „Umbuchung läuft“ bzw. das Ergebnis.
3. **Sonderfälle auflösen statt loggen.** `4005`: Buchung über `GET /bookings?clientReference=…` bei LiteAPI suchen, bei Erfolg weiter mit Capture. Capture abgelehnt: Reservierung `NEEDS_ATTENTION`, im Frontend als „Wird geprüft“ mit Hinweis statt Fehler.
4. **Demo-Anbieter für Unterkünfte.** Eine zweite Implementierung hinter `LiteApi` (`nfv.bookings.alternatives.provider=demo`) mit festen, realistischen Hotels in Neuseeland, Prebook und Buchung immer erfolgreich. PayPal läuft dabei echt gegen die Sandbox. So hängt die Vorführung nur an PayPal, und genau das soll zu sehen sein. Für den echten Betrieb bleibt LiteAPI.
5. **Ende-zu-Ende-Lauf gegen die Sandbox** auf dem Test-Server (feste Domain, kein ngrok): einmal mit Demo-Anbieter, einmal mit LiteAPI-Sandbox. Dabei Feldnamen und Status prüfen und als Test-Fixture für `PayPalClientTest` festhalten.
6. **Webhooks** (`CHECKOUT.ORDER.APPROVED`, `PAYMENT.AUTHORIZATION.VOIDED`, `PAYMENT.CAPTURE.COMPLETED/DENIED/REFUNDED`) auf dem Test-Server, sie stoßen nur den Job aus 2 an. Optional, der Job reicht.

## PayPal-Mehrwert sichtbar machen

- **Ein Tipp in der Notlage:** Smart Buttons im Sheet (oben, 1). Für die Präsentation der wichtigste Punkt: kein Formular, keine Kartendaten, kein Verlassen der App.
- **Geld erst bewegen, wenn das Zimmer sicher ist:** Autorisieren → buchen → capturen, sonst freigeben. Gibt es schon, muss nur in der Oberfläche erklärt werden („Betrag ist reserviert, abgebucht wird erst, wenn das Hotel bestätigt“).
- **PayPal merken für das nächste Mal (Vault):** Beim ersten Umbuchen „PayPal für Notfälle speichern“ (`payment_source.paypal.attributes.vault`), danach Umbuchen ohne Freigabefenster. Passt genau zum Fall „abends an der Rezeption“. Vor dem Bau prüfen, ob Vaulting in der Sandbox-App freigeschaltet werden kann und mit `intent=AUTHORIZE` geht. Stretch-Ziel.
- **Erstattung automatisch:** Storniert der Nutzer eine in der App gebuchte Alternative innerhalb der Frist, LiteAPI-Storno und PayPal-Refund (`refundCapture` gibt es) in einem Schritt.
- **Kosten teilen** (später, braucht geteilte Trips): Mitreisende zahlen ihren Anteil per PayPal-Link, Weiterleitung per Payouts (gibt es).
- **Pay Later** für teure Notfallbuchungen: im JS SDK nur ein Parameter, Verfügbarkeit je Land, für Neuseeland vor der Demo prüfen.

## Zusatzfeatures

Sortiert nach Nutzen für die Kernidee.

1. **Push-Benachrichtigungen** (Web Push, Service Worker gibt es schon): bei neuer oder verschärfter Consequence `TOO_LATE`/`DELAYED`, mit Lösung im Text. Ohne Push bleibt die App passiv, und die Kernidee kommt nicht zur Geltung. iOS nur für installierte PWAs.
2. **Incidents nahe der Unterkunft** (`booking_accommodation.location`, GIST-Index aus `V28`): Ereignisse im Umkreis (z. B. 2 km, `ST_DWithin`) während des Aufenthalts, etwa Sperrung der Zufahrt, Unwetter, Überflutung. Neue Consequence-Art am Stop statt an der Route, Plan-Option `STAY_NEARBY`. Schließt „Incidents an Unterkünften“ aus [domain.md](domain.md#offen).
3. **Späteste Ankunft aus der Buchung:** Frist = `checkInUntil` der Unterkunft statt nur `Stop.arrivalAt` (offen in consequence-assessment.md). Damit wird „komme nicht mehr rein“ präzise und `NOTIFY_HOST` sinnvoll.
4. **Nachricht an die Unterkunft:** vorformulierte E-Mail/SMS in der Landessprache („Wir kommen wegen einer Sperrung auf SH 6 erst gegen 22 Uhr“) bzw. Storno-Bitte an die alte Unterkunft nach dem Umbuchen. Öffnet `mailto:`/`sms:`, die App sendet nichts selbst.
5. **Demo-Knopf „Störung simulieren“** für die Präsentation (nur für Testkonten, `TestTrafficEventController` gibt es): legt eine Sperrung vor dem nächsten Stop an, Push kommt, Plan zeigt eine Unterkunft davor.
6. **Assistent per MCP lesend:** `get_rescue_plan` für Claude/ChatGPT, Bezahlen bleibt in der App.

## UX-Überarbeitungen der bestehenden Oberfläche

Aus Code und Mockups abgeleitet, nicht am Gerät durchgeklickt. Bitte ergänzen, was dir aufgefallen ist.

- **Reiseansicht mit Lage oben:** Statuszeile über der Karte, „Alles im Plan · Ankunft Wanaka 17:40“ oder „1 Problem · Lösung ansehen“ (gelb). Heute erschließt sich die Lage nur über Chips auf der Karte.
- **„Meine Reisen“:** laufende Reise groß mit Status, geplante darunter, Zähler nur für echte Probleme (gibt es schon im Backend).
- **KI-Hinweise entfernen:** „Sortiert vom Assistenten nach Gleichwertigkeit ✦“, „Empfehlung ✦“, „Vom Assistenten im Web gefunden“. Statt ✦ eine neutrale Markierung „bitte prüfen“ nur dort, wo der Nutzer etwas bestätigen muss (ausgelesene Buchungen).
- **Interne Sätze raus:** z. B. „Alternativen werden bei Bedarf neu berechnet. Gespeichert wird nur, was du auswählst.“ im `TripAlternativesSheet`.
- **Formulare hinter „Anpassen“:** Kriterien und Gastdaten im Umbuchungsfluss einklappen, vorbelegt aus Buchung und Profil.
- **Fortschritt beim Umbuchen:** drei Schritte sichtbar („Preis gesichert“, „Zahlung reserviert“, „Hotel bestätigt“), statt einem Spinner. Fehler in Alltagssprache mit nächstem Schritt.
- **Bestätigungsseite:** neue Unterkunft mit Karte, „Navigation starten“ (Google/Apple Maps-Link), Buchungsnummer, „Alte Buchung stornieren“ mit Link.
- **Planung schneller:** „Neue Reise“ direkt mit „Buchungsbestätigung einfügen“ starten, Stops und Zeiten daraus ableiten. Der schnellste Weg zu einer bewertbaren Reise.
- **Kleinkram:** einheitliche Leer- und Ladezustände (Skeletons wie im `RouteSheet`), Ziel-Größe von Buttons ≥ 44 px im Sheet, Zurück-Verhalten der Sheets (Browser-Zurück schließt das Sheet).

## Priorisierte Umsetzung

**P0, Kern für die Demo**

1. Demo-Anbieter für Unterkünfte + Ende-zu-Ende-Lauf mit PayPal-Sandbox auf dem Test-Server.
2. PayPal Smart Buttons im Sheet statt Weiterleitung, Weiterleitung als Fallback.
3. Hintergrund-Job für offene Reservierungen, Sonderfälle `4005` und Capture-Fehler auflösen.
4. Plan-Endpunkt mit `STAY_NEARBY`, `STAY_BEFORE`, `KEEP` (Route zunächst über das bestehende `RouteSheet`), Plan-Sheet mit einem Tipp, Gastdaten im Profil.
5. Einstieg „Problem mit meiner Unterkunft“ am aktuellen Stop.
6. KI-Hinweise und interne Texte entfernen, Statuszeile auf der Reise.

**P1, macht die Kernidee rund**

7. `Suggestions.forConsequence` (Umfahrung im Plan, `REROUTE`) und `SHIFT_DEPARTURE`.
8. Web Push für `TOO_LATE`/`DELAYED`.
9. Demo-Knopf „Störung simulieren“.
10. Frist aus `checkInUntil`, Option `NOTIFY_HOST`.
11. Storno einer in der App gebuchten Alternative mit automatischem Refund.

**P2, danach**

12. Incidents nahe der Unterkunft (`V28`, `ST_DWithin`).
13. PayPal Vault (ohne Freigabefenster), Pay Later.
14. Webhooks, MCP-Tool `get_rescue_plan`, Kosten teilen.

## Entscheidungen für Philipp

1. **Demo-Anbieter für Unterkünfte:** einverstanden, dass die Vorführung mit festen Demo-Hotels läuft und PayPal echt? (Empfehlung: ja, LiteAPI bleibt für den Betrieb.)
2. **Smart Buttons statt Weiterleitung:** einverstanden mit dem PayPal JS SDK im Frontend? (Empfehlung: ja, löst das PWA-Problem.)
3. **Max. Preis ohne Formular:** bisheriger Preis + 25 % als Standard, oder ohne Obergrenze und nur nach Preis sortiert?
4. **Wie unsichtbar ist die KI:** alle ✦ entfernen, auch bei ausgelesenen Buchungen, oder dort als neutrale „bitte prüfen“-Markierung behalten? (Empfehlung: neutrale Markierung.)
5. **Push in P0 oder P1:** ohne Push wirkt die App in der Demo passiv; mit dem Demo-Knopf reicht für die Präsentation auch das Banner. (Empfehlung: P1.)
