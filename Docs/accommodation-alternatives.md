# Konzept: Alternative Unterkünfte suchen (LiteAPI + Assistant)

- **Status:** umgesetzt (2026-11-03), Zahlung nur über PayPal, das Payment SDK von LiteAPI wird nicht genutzt. Abweichungen vom ursprünglichen Konzept stehen unter [Umsetzung](#umsetzung-stand-2026-11-03).
- **Ziel:** Zu einer hinterlegten Unterkunft-Buchung eine Alternative finden, die alle übergebenen Kriterien erfüllt, und sie in der App buchen.
- **Kriterien (erste Stufe):** maximaler Preis, Region (Adresse bzw. Koordinaten + Umkreis), Verfügbarkeit für die Gästezahl, Anzahl Zimmer, Datum von/bis.
- **Erster Use-Case „Buchung nicht nutzbar“:** falsch gebucht, Hotel hat die Buchung vergessen, überbucht o. Ä. Die Kriterien werden aus der aktuellen Unterkunft befüllt, der Nutzer kann sie vor der Suche ändern.
- **Später:** dieselbe Suche mit anderen Parametern, z. B. äquivalente Unterkunft an einem anderen Ort (Route gesperrt, Stop verschoben). Deshalb sind Kriterien ein eigenes Objekt, der Use-Case befüllt es nur vor.
- Liegt fachlich im Modul `bookings` (Altbestand bleibt unberührt).

## Annahme: „Lite-API“ = LiteAPI von Nuitee

- Gemeint ist **LiteAPI** (https://liteapi.travel, Doku https://docs.liteapi.travel), eine Hotel-API von Nuitee für Suche, Verfügbarkeit, Preise, Prebook, Buchung und Storno. Bitte bestätigen, falls ein anderer Dienst gemeint ist.
- Geprüft am 2026-11-02 anhand der Referenz (REST `v3.0`):
  - Suche und Stammdaten unter `https://api.liteapi.travel/v3.0`, Buchung unter `https://book.liteapi.travel/v3.0`. Auth per Header `X-API-Key`, Sandbox-Keys beginnen mit `sand_`.
  - `POST /hotels/rates`: `latitude`, `longitude`, `radius` (Meter); `checkin`, `checkout` (`YYYY-MM-DD`); `occupancies` (je Zimmer `{adults, children: [Alter]}`); `currency`, `guestNationality`; Filter `starRating`, `boardType`, `refundableRatesOnly`, `maxRatesPerHotel`, `limit`, `sort`, `includeHotelData`, `margin`, `timeout`.
  - Antwort je Hotel `roomTypes[]` mit `offerId`, darin `rates[]` mit `boardType`, `retailRate.total` (Gesamtpreis), `suggestedSellingPrice`, `cancellationPolicies` (`refundableTag` `RFN`/`NRFN`, Fristen mit Zeitzone).
  - `GET /data/hotel?hotelId=…`: Name, Adresse, Koordinaten, Sterne, Ausstattung, Check-in-Zeiten, Bilder.
  - `POST /rates/prebook` (`offerId`, `usePaymentSdk`): sichert Preis und Verfügbarkeit, meldet Preis-, Storno- und Verpflegungsänderungen, liefert `prebookId`, mit Payment SDK zusätzlich `transactionId` + `secretKey`.
  - `POST /rates/book` (`prebookId`, `holder`, `guests`, `payment.method`, `clientReference`): liefert `bookingId`, `status`. Doppelte `clientReference` → Fehler `4005`.
  - `GET /bookings/{bookingId}`, `PUT /bookings/{bookingId}` (Storno, liefert Gebühr und Erstattung).
  - Payment SDK (Script von `payment-wrapper.liteapi.travel`): Kartenzahlung (laut Doku auch Google Pay, kein PayPal) im Browser, danach Buchung mit `TRANSACTION_ID`. Nuitee ist dann Merchant of Record, wir bekommen eine Kommission (`margin` in Prozent, `0` = Nettopreis), wöchentlich ausgezahlt nach dem Check-out.
  - Eigene Zahlung (wir sind Merchant of Record): Rates mit `margin: 0` (Nettopreis), eigene Provision über einen eigenen Zahlungsweg, Buchung mit `ACC_CREDIT_CARD` (belastet die im LiteAPI-Konto hinterlegte Karte; in der Sandbox eine versteckte Testkarte je Key). `CREDIT` (Kreditlinie) nur nach Vertrag und nicht in der Sandbox.
  - Preise unter dem empfohlenen Verkaufspreis (`suggestedSellingPrice`) nur für angemeldete Nutzer bzw. in der App, nicht öffentlich.
  - Fehler `2001` bei Prebook oder Book: Angebot nicht mehr verfügbar, neu suchen.
  - Es gibt einen LiteAPI-MCP-Server, wird bewusst nicht genutzt (siehe Rollen).

## Rollen: Assistant und LiteAPI

- **Grundsatz:** Ob ein Angebot die Kriterien erfüllt, entscheidet das Backend anhand der Daten von LiteAPI, nicht das Sprachmodell. Das Modell wählt und begründet unter den Angeboten, die alle Kriterien schon erfüllen. Es erfindet keine Preise oder Verfügbarkeit und löst keine Buchung aus.

| Aufgabe | wer |
|---|---|
| Kriterien aus der aktuellen Buchung vorbefüllen | Backend, deterministisch (`AlternativeCriteria.from(booking)`) |
| Verfügbarkeit für Gäste und Zimmer im Zeitraum, Preise, Storno | LiteAPI (`/hotels/rates`) |
| Harte Prüfung jedes Angebots gegen alle Kriterien (Preis ≤ Max., Abstand ≤ Umkreis, Zeitraum, Belegung) | Backend, nach der Antwort von LiteAPI |
| „Gleichwertig zur bisherigen Unterkunft“: Reihenfolge der geprüften Angebote, beste Alternative, kurze Begründung | Assistant (`StructuredExtraction`, Schema in `bookings.logic`) |
| Preis sichern, buchen, ggf. stornieren | LiteAPI, nur nach Bestätigung des Nutzers |
| Zahlung | PayPal (Modul `paypal`) oder später Payment SDK, siehe [Zahlung](#zahlung); nie das Modell |

- **Was das Modell bekommt:** die bisherige Unterkunft (Name, Art, Sterne falls bekannt, Lage, Preis pro Nacht, Hausregeln) und je Kandidat nur Fakten (Hotel-Id, Name, Sterne, Bewertung, Abstand, Gesamtpreis, Verpflegung, stornierbar ja/bis, Check-in-Zeit, wichtige Ausstattung). Höchstens Top-N (z. B. 20, vorsortiert nach Abstand und Preis).
- **Was es liefert:** Liste von Hotel-Ids mit Rang und Begründung. Ids, die nicht unter den Kandidaten sind, verwirft das Backend.
- **Ohne Assistant** (kein OpenAI-Key, Rate-Limit, Fehler): die Suche funktioniert trotzdem, Reihenfolge nach Abstand und Preis, ohne Begründung. In der Notlage soll die Suche nicht am Modell scheitern.
- **Ohne LiteAPI-Key:** die Funktion wird nicht angeboten (Status-Endpunkt wie `GET /bookings/assistant`).
- **Warum nicht `aiSearch` oder der MCP-Server von LiteAPI:** Kriterien müssen nachprüfbar hart gelten, und Buchen ist teuer und nicht umkehrbar. Ablauf, Bestätigung und Speicherung bleiben im Backend.

## Kriterien

- `AlternativeCriteria` (Record in `bookings.logic`), unabhängig vom Use-Case:
  - `latitude`, `longitude`, `radiusMeters` (Region). Adresse nur zur Anzeige.
  - `checkIn`, `checkOut` (`LocalDate`, Ortszeit der Unterkunft).
  - `guests` (Erwachsene), `rooms`. Verteilung auf `occupancies`: Gäste gleichmäßig auf Zimmer (5 Gäste, 2 Zimmer → 3 + 2).
  - `maxPrice` + `currency` (Gesamtpreis des Aufenthalts).
  - Später optional: Kinder mit Alter, Sterne, Verpflegung, nur stornierbar, Ausschluss von Hotels (z. B. das bisherige).
- **Vorbefüllung im Use-Case „Buchung nicht nutzbar“:**

  | Kriterium | aus der Buchung | fehlt der Wert |
  |---|---|---|
  | Region | Koordinaten von `AccommodationDetails` (sonst des Stops), Umkreis Standard 5 km (`nfv.bookings.alternatives.default-radius`) | Nutzer muss einen Ort wählen (Photon wie beim Stop) |
  | Datum von/bis | Datum von `startsAt`/`endsAt` in der Zone der Unterkunft; liegt der Check-in in der Vergangenheit (Aufenthalt läuft), ab heute am Ort | Pflichteingabe |
  | Gäste | `guests` | Pflichteingabe, vorgeschlagen 2 |
  | Zimmer | `rooms` | 1 |
  | Max. Preis | `price` + `currency` der Buchung, Standard ohne Aufschlag (Nutzer erhöht bei Bedarf); bei verkürztem Zeitraum anteilig pro Nacht | ohne Obergrenze, Nutzer kann eine setzen |

- Ausgeschlossen wird die bisherige Unterkunft, wenn ihr `placeRef` eine LiteAPI-Hotel-Id ist; sonst Treffer mit sehr kleinem Abstand (< 50 m) und ähnlichem Namen als „bisherige Unterkunft?“ markieren.
- Spätere Use-Cases befüllen anders, z. B. „anderer Ort“: Region aus einem anderen Stop oder Ort, übrige Werte aus der Buchung.

## Ablauf

1. **Einstieg:** in den Details einer Unterkunft-Buchung „Alternative suchen“ (optional Grund: falsch gebucht, Hotel hat keine Buchung, sonstiges).
2. **Kriterien prüfen:** Formular mit den vorbefüllten Werten (Herkunft „aus der Buchung“ gekennzeichnet, fehlende Pflichtwerte markiert). Nutzer kann alles ändern.
3. **Suche:** Backend ruft `/hotels/rates` mit Koordinaten, Umkreis, Zeitraum, `occupancies`, `currency` = Währung des Max. Preises, `includeHotelData`, `maxRatesPerHotel` klein, `limit` begrenzt.
4. **Harte Prüfung:** je Hotel das günstigste Angebot, das alle Kriterien erfüllt (Gesamtpreis ≤ Max., Abstand per Haversine ≤ Umkreis, Belegung passt). Hotels ohne passendes Angebot fallen weg.
5. **Auswahl durch den Assistant** (falls verfügbar) über die Top-N, Ergebnis: empfohlene Alternative plus geordnete weitere Treffer mit Begründung (✦ wie beim Auslesen).
6. **Ergebnis:** Liste und Karte mit der bisherigen Unterkunft als Bezug (Abstand, Preisunterschied). Keine Treffer → Hinweis, welches Kriterium am meisten ausschließt (z. B. „ab 7 km gäbe es 4 Treffer“, durch erneute Suche mit größerem Umkreis, nur auf Wunsch).
7. **Angebot wählen → Prebook:** `/rates/prebook` (bei PayPal `usePaymentSdk: false`). Neuer Preis wird erneut gegen den Max. Preis geprüft; Preis-, Storno- oder Verpflegungsänderung wird vor der Zahlung angezeigt.
8. **Bestätigen und zahlen:** Zusammenfassung mit Endpreis (Nettopreis + Provision), Gastdaten (Name, E-Mail aus dem Profil vorbelegt), Zahlung per PayPal, siehe [Zahlung](#zahlung).
9. **Buchen:** `/rates/book` mit `ACC_CREDIT_CARD` (bzw. `TRANSACTION_ID` beim Payment SDK) und `clientReference` (Id der Reservierung, schützt vor doppelter Buchung). Danach legt `BookingLogic` eine neue `Booking` am selben Stop an: `source` `SEARCH`, Buchungsnummer, Preis, Stornofrist, Zeiten über die bestehende Zeitzonenlogik, `AccommodationDetails` aus den Hotel-Stammdaten, `placeRef` `liteapi:hotel/<hotelId>`.
10. **Bisherige Buchung:** wird nicht automatisch geändert. Der Nutzer wird gefragt, ob sie als storniert markiert werden soll (`Booking.cancel()`, gibt es schon); stornieren beim fremden Anbieter muss er selbst (Link `manageUrl`).

## Zahlung

- **Entscheidung (2026-11-02):** Erster Zahlungsweg ist PayPal über das bestehende Modul `paypal`, das dafür angepasst und weiterverwendet wird (kein Altbestand mehr). Wir sind Merchant of Record und behalten eine Provision ein.
- **Preis:** Rates mit `margin: 0`, Endpreis = Nettopreis + Provision (`nfv.bookings.alternatives.fee-percent`, optional Mindestbetrag). Der Max. Preis der Kriterien gilt für den Endpreis. Provision so wählen, dass der Endpreis in der Regel nicht über dem `suggestedSellingPrice` liegt.
- **Ablauf mit PayPal (Orders API v2, `intent=AUTHORIZE`):**
  1. Prebook erfolgreich → `PayPalCheckout` legt eine Order über den Endpreis an (`reference` = Id der Reservierung, idempotent), Nutzer bestätigt bei PayPal (`approveUrl`, Rückkehr auf die Unterroute der Alternativen).
  2. Backend autorisiert die Zahlung (Geld reserviert, noch nicht eingezogen) und prüft Betrag und Währung.
  3. Buchung bei LiteAPI mit `ACC_CREDIT_CARD`.
  4. Erfolgreich → Capture der Autorisierung, Reservierung `BOOKED`, `Booking` anlegen. Gescheitert (`2001`, Preisänderung) → Autorisierung freigeben (void), kein Geld bewegt, keine Erstattungsgebühr.
  - Bricht der Ablauf zwischen 3 und 4 ab, gleicht ein Poller (wie beim Top-up in `accounting`) Reservierungen mit Autorisierung über `clientReference` bei LiteAPI ab und captured oder gibt frei. Autorisierungen laufen nach einigen Tagen ab, das reicht für den Abgleich.
- **Storno einer per App gebuchten Alternative:** LiteAPI erstattet laut Bedingungen auf unsere Karte, wir erstatten per PayPal (Refund der Capture). Provision bei Storno einbehalten oder erstatten: offen.
- **Anpassungen am Modul `paypal`** (bleibt technische Anbindung ohne Fachwissen, `PayPalModuleTest` gilt weiter):
  - Return- und Cancel-URL je Aufruf statt fest konfiguriert (`checkout-return-url` zeigt auf eine entfernte Seite von `accounting`).
  - `intent=AUTHORIZE` mit Authorize, Capture und Void der Autorisierung (`/v2/checkout/orders/{id}/authorize`, `/v2/payments/authorizations/{id}/capture|void`).
  - Refund einer Capture (`/v2/payments/captures/{id}/refund`), idempotent per `PayPal-Request-Id`.
  - `PayPalConnect` und `PayPalPayouts` bleiben unverändert (siehe [Weitere PayPal-Potenziale](#weitere-paypal-potenziale)).
  - Abhängigkeit neu: `bookings --> paypal`. `accounting` und `onboarding` bleiben Altbestand.
- **Später zusätzlich Payment SDK („Mit Karte / Google Pay zahlen“):** technisch möglich, beide Wege nebeneinander anzubieten. Beim Payment SDK ist Nuitee Merchant of Record, bei PayPal wir. Folgen:
  - Zwei Preismodelle: SDK über `margin` (Provision von Nuitee ausgezahlt), PayPal über `margin: 0` + eigene Provision. Endpreise sollten gleich sein, sonst sieht der Nutzer je Zahlart einen anderen Preis; deshalb Angebote je Zahlart getrennt holen oder `margin` und Provision gleich setzen.
  - Storno und Erstattung laufen je nach Weg bei Nuitee oder bei uns; die Reservierung merkt sich den Weg (`payment_method`).
  - Rechtlich und steuerlich zwei Rollen gleichzeitig.
- **Alternative zu zwei Anbietern:** Karte, Google Pay und Apple Pay auch über PayPal anbieten (PayPal JS SDK mit Card Fields und Wallet-Buttons). Dann bleibt es bei einem Merchant of Record, einem Preismodell und einer Abrechnung. Für den Hackathon reicht der PayPal-Button.

## Weitere PayPal-Potenziale

- Ideen, nicht beschlossen. Hinweise zu PayPal-Produkten aus allgemeinem Wissen, vor der Umsetzung in der PayPal-Doku prüfen.
- **Schnelle Zahlung in der Notlage:** PayPal-Konto des Nutzers speichern (Vault), Alternative dann mit einem Tippen bezahlen, ohne Weiterleitung. Passt zum Use-Case abends an der Rezeption.
- **Erstattung bei Storno:** Refund wie oben, auch automatisch, wenn LiteAPI eine Buchung storniert.
- **Kosten teilen unter Mitreisenden:** Trips teilen ist offen ([domain.md](domain.md#offen)). Danach Anteil einer Buchung von Mitreisenden per PayPal einsammeln, Weiterleitung an den Buchenden über `PayPalPayouts` (gibt es schon).
- **Bezahlte Funktionen:** z. B. Benachrichtigungen bei Incidents als Abo (PayPal Subscriptions). In [architecture.md](architecture.md#altbestand) schon als Idee für `accounting` genannt.
- **Später bezahlen** (PayPal Pay Later / Ratenzahlung) für teure Alternativen, je nach Land verfügbar.
- **Weitere Buchungstypen:** Transport und Aktivitäten (geplant) über denselben Checkout.
- **Zahlung über KI-Assistenten:** MCP-Tools (`/mcp`) könnten eine Alternative vorschlagen und einen PayPal-Link zur Freigabe schicken; die Zahlung bestätigt der Nutzer immer selbst bei PayPal. PayPal bietet dafür eigene Agent-Werkzeuge an, zu prüfen.
- **Gastdaten vorbelegen:** „Log in with PayPal“ (`PayPalConnect`) liefert Name und E-Mail; bewusst getrennt vom Login der App (`auth` bleibt isoliert).

## Umsetzung im Modul `bookings`

- `bookings.logic`:
  - `AlternativeCriteria` (Record) und `AlternativeSearch` (`@Service`): `criteriaFor(appUserId, bookingId)`, `search(appUserId, bookingId, criteria)`.
  - `AlternativeRanking` (paketprivat): Anweisung und Schema für `StructuredExtraction`, wie `AccommodationExtractor`.
  - `AlternativeReservations` (`@Service`): Prebook, Buchen, Abgleich, später Storno.
  - LiteAPI-Client `LiteApiClient` (paketprivat oder `bookings.logic.liteapi`), `RestClient` direkt wie im Assistant, kein SDK. Liefert eigene Records, keine LiteAPI-Typen nach außen.
- `bookings.config`: `@ConfigurationProperties` `nfv.bookings.alternatives.*`: `api-key` (`LITEAPI_API_KEY`, im Stack Docker Secret), `base-url`, `book-url`, `margin`, `default-radius`, `max-candidates`, `timeout`, `searches-per-hour` (je Nutzer).
- **Abhängigkeiten:** wie bisher `bookings --> trips` (Eigentum, Stop) und `bookings --> assistant`, neu `bookings --> paypal`. LiteAPI kommt als externes System hinzu (System Context), PayPal ist dort schon. `assistant` bleibt ohne Fachwissen und unverändert; das Ranking zählt gegen dessen Limit je Nutzer.
- **Alternative:** Wird LiteAPI später auch außerhalb von `bookings` gebraucht, den Client in ein technisches Modul wie `assistant` auslagern, das keine Fachmodule kennt.

## Persistenz

- **Suchergebnisse werden nicht gespeichert** (Preise und Angebote verfallen). Optional kurzer Cache im Speicher je Suche (wenige Minuten) für Details und Prebook.
- **Neue Tabelle `booking_reservation`** (Flyway, nächste freie Version, laut [architecture.md](architecture.md#persistence) derzeit `V29`), eine Zeile je Buchungsversuch über LiteAPI:
  - `stop_id` (FK), `replaces_booking_id` (FK auf `booking`, die nicht nutzbare Buchung, leer bei anderen Use-Cases), `booking_id` (FK, `UNIQUE`, leer bis gebucht), `hotel_id`, `offer_id`, `prebook_id`, `provider_booking_id`, `client_reference` (`UNIQUE`), `status` (`PREBOOKED`, `AUTHORIZED`, `BOOKED`, `FAILED`, `CANCELLED`), `net_price` laut Prebook, `fee`, `price` (Endpreis), `currency`, `payment_method` (`PAYPAL`, später `LITEAPI_SDK`), `paypal_order_id`, `paypal_authorization_id`, `paypal_capture_id`, `created_at`, `booked_at`.
  - Zweck: Wiederaufnahme, wenn Zahlung erfolgte, die Antwort aber verloren ging (Abgleich über `clientReference`/`GET /bookings`), späteres Storno mit der LiteAPI-Id, Nachvollziehbarkeit gegenüber Nuitee.
  - Innerhalb des Moduls, daher `@ManyToOne` zu `Booking` zulässig. Envers für Statuswechsel, `_aud` in derselben Migration.
- `booking` und `booking_accommodation` bleiben unverändert. Die neue Buchung bringt Koordinaten mit, damit greift auch `location` (`V28`).
- **Grund „nicht nutzbar“:** zunächst nur an der Reservierung (`reason` `VARCHAR(32)`), kein neuer `BookingStatus`. Ob die alte Buchung einen eigenen Status wie `UNUSABLE` braucht, ist offen.

## API (Vorschlag)

- `GET /bookings/alternatives/status` (LiteAPI verfügbar? Assistant verfügbar?).
- `GET /accommodation-bookings/{id}/alternative-criteria` → vorbefüllte Kriterien mit fehlenden Pflichtfeldern.
- `POST /accommodation-bookings/{id}/alternative-searches` (Body: Kriterien) → geprüfte Treffer, Empfehlung, Begründungen.
- `POST /accommodation-bookings/{id}/alternative-reservations` (Body: `offerId`) → Reservierung nach Prebook mit Endpreis und `approveUrl` der PayPal-Order.
- `POST /booking-reservations/{id}/booking` (nach Rückkehr von PayPal) → autorisieren, bei LiteAPI buchen, capturen; liefert die neue `Booking`. Fehler: 422 `PAYMENT_NOT_APPROVED`/`PAYMENT_DECLINED` (wie in `accounting`).
- Später allgemein `POST /stops/{stopId}/accommodation-searches` für Use-Cases ohne bestehende Buchung, gleiche Kriterien.
- Fehler über `BookingExceptionHandler`: 503 `ALTERNATIVES_UNAVAILABLE`, 409 `OFFER_EXPIRED` (LiteAPI `2001`), 409 `PRICE_EXCEEDED` (Prebook über Max. Preis), 429 `ALTERNATIVES_RATE_LIMITED`, 400 `INVALID_BOOKING_INPUT` (Kriterien unvollständig).
- **MCP:** später lesend `find_alternative_accommodation`; Buchen bewusst nicht per MCP (Zahlung bestätigt der Nutzer bei PayPal).

## Frontend

- Button „Alternative suchen“ in den Buchungsdetails, Unterroute `buchungen/:bookingId/alternativen`.
- Kriterien-Formular, Ergebnisliste mit Karte, „Mit PayPal bezahlen“ (Weiterleitung, Rückkehr auf die Unterroute). PayPal-Rückkehr braucht eine öffentliche URL, lokal per ngrok wie in [paypal.md](paypal.md#lokal-ohne-localhost-sandbox).
- Später Payment SDK: Script von `payment-wrapper.liteapi.travel`, dafür CSP und Service Worker (nicht cachen) anpassen.
- Mobil zuerst: typischer Fall ist abends vor der Rezeption.

## Datenschutz und Recht

- An LiteAPI: Koordinaten, Zeitraum, Belegung, bei Buchung Name und E-Mail von Buchendem und Gästen. An OpenAI: Fakten der bisherigen Unterkunft und der Kandidaten, keine Namen oder Kontaktdaten der Reisenden, keine Buchungsnummer. Hinweise im Frontend und in der Datenschutzerklärung.
- Zahlung nur bei PayPal (bzw. im Payment SDK), Zahlungsdaten erreichen weder Backend noch Modell. An PayPal gehen Endpreis und eine Beschreibung ohne Gastdaten.
- Mit PayPal sind wir Merchant of Record: wir verkaufen die Übernachtung weiter. Zu klären vor dem Live-Betrieb: Rolle als Vermittler oder Reiseverkäufer (Informationspflichten, AGB, Widerruf, Impressum), Steuern auf die Provision, Haftung bei Storno durch das Hotel. Für den Hackathon nur Sandbox.

## Tests

- LiteAPI-Client, `StructuredExtraction` und `PayPalCheckout` in Integrationstests gemockt, `nfv.bookings.alternatives.api-key=` leer in den Test-Properties. Neue PayPal-Aufrufe in `PayPalClientTest` gegen `MockRestServiceServer`.
- Zahlfälle: Buchung scheitert nach Autorisierung → Void, kein Capture; Abbruch zwischen Buchung und Capture → Poller captured; Capture doppelt → idempotent.
- Fälle: Vorbefüllung (fehlende Gäste/Koordinaten, laufender Aufenthalt, anteiliger Preis), harte Prüfung verwirft zu teure und zu weit entfernte Angebote, Ranking verwirft fremde Ids, Suche ohne Assistant, Prebook über Max. Preis → `PRICE_EXCEEDED`, `2001` → `OFFER_EXPIRED`, Buchung idempotent über `clientReference`, neue Booking mit `source` `SEARCH`.
- Manuell gegen die Sandbox (`sand_`-Key, Testkarten des Payment SDK).

## Umsetzung (Stand 2026-11-03)

- **Code:** `bookings.logic` `LiteApi` (Client, eigene Records), `AlternativeSearch` (Kriterien, harte Prüfung), `AlternativeRanking` (Assistant), `AlternativeReservations` (Ablauf) mit `ReservationSteps` (eine Transaktion je Schritt), `Pricing`, `SearchLimiter`; `bookings.api.AlternativeController`; `bookings.config.AlternativesProperties` (`nfv.bookings.alternatives.*`); Frontend `src/bookings/AlternativesSheet.tsx`.
- **Zahlung nur PayPal** (Entscheidung 2026-11-03), kein Payment SDK. Buchung bei LiteAPI immer mit `ACC_CREDIT_CARD`, Rates und Prebook mit `margin: 0`.
- **Provision:** `fee-percent` (Standard 10 %) auf den Nettopreis, gedeckelt auf den `suggestedSellingPrice`, falls der über dem Nettopreis liegt. Der Max. Preis gilt für den Endpreis.
- **Status** der Reservierung: `PREBOOKED`, `PAYMENT_PENDING` (Order angelegt), `AUTHORIZED`, `PROVIDER_BOOKED` (bei LiteAPI gebucht, Geld noch nicht eingezogen), `BOOKED`, `FAILED`.
- **Wiederaufnahme:** `POST /booking-reservations/{id}/booking` setzt beim letzten gespeicherten Schritt fort und ist wiederholbar (PayPal-Request-Ids, `clientReference` `nfv-stay-<id>`). Seit 2026-11-06 macht das auch ein Job im Hintergrund, `4005` wird über die Suche nach der `clientReference` aufgelöst, ein abgelehnter Capture führt zu `NEEDS_ATTENTION`, siehe [paypal.md](paypal.md#stabilität).
- **Fehler von LiteAPI:** einen Code `2001` gibt es laut Referenz nicht. Jede 4xx-Antwort auf Prebook oder Book (außer 401/403) ist `OFFER_UNAVAILABLE`, 5xx und Netzwerkfehler `ALTERNATIVES_UNAVAILABLE`.
- **Gäste:** LiteAPI braucht je Zimmer einen Gast; eingetragen wird der Buchende für jedes Zimmer. Gästezahl wird gleichmäßig auf die Zimmer verteilt (`Pricing.adultsPerRoom`).
- **Hotel und Gastdaten** stehen in der Reservierung (Tabelle `booking_reservation`, `V30`), nicht historisiert. Gastdaten werden bei `BOOKED` oder `FAILED` gelöscht. Hotel-Daten kommen aus `GET /data/hotel`, Check-in/Check-out-Zeiten daraus, sonst 15:00/11:00.
- **Grund** („falsch gebucht“ usw.) wird nicht gespeichert. Die bisherige Buchung wird nur auf Wunsch in der App als storniert markiert (`POST /bookings/{id}/cancellation`).
- **Storno** einer gebuchten Alternative mit PayPal-Erstattung seit 2026-11-06 ([paypal.md](paypal.md#stabilität)). Die Provision wird bei kostenlosem Storno mit erstattet.
- **Zahlung im Sheet** per PayPal-Button statt Weiterleitung ([paypal.md](paypal.md#paypal-button-im-sheet)). Gastdaten merkt sich das Gerät (`localStorage`), sind sie vollständig, geht es nach der Wahl eines Angebots direkt zur Zahlung. Sind die Kriterien aus der Buchung vollständig, sucht das Sheet sofort, das Formular gibt es nur unter „Suche anpassen“.
- **Nicht umgesetzt:** Abgleich per `GET /bookings/{id}`, MCP-Tools, Ausschluss der bisherigen Unterkunft über ähnliche Namen (nur über `placeRef` und die Markierung „möglicherweise die bisherige Unterkunft“ bis 50 m).
- **Lokal ausprobieren:** `LITEAPI_API_KEY` (Sandbox-Key `sand_…`), `PAYPAL_CLIENT_ID`/`PAYPAL_CLIENT_SECRET` (Sandbox), Frontend über ngrok (PayPal akzeptiert `localhost` nicht als Return-URL), siehe [paypal.md](paypal.md#lokal-ohne-localhost-sandbox). `OPENAI_API_KEY` optional für das Ranking. Im Stack Docker Secret `nfv_liteapi_api_key`, siehe [deployment.md](deployment.md).

## Route nach dem Umbuchen

- **Ausgangslage:** Routen laufen von Stop zu Stop über die Koordinaten der Stops (`RoutePlanning`), die Unterkunft spielt dafür keine Rolle. Liegt die Alternative im selben Ort, ändert sich an der Route nichts.
- **Anderer Ort:** mehr als 10 km vom Stop (`trips.logic.StayPlaces.OTHER_PLACE_METERS`, Luftlinie). Nach der Buchung (`AlternativeReservations.followStay`, auch im Hintergrund-Job) zieht der Stop zum Hotel (`TripEditing.moveStopToStay`):
  - Koordinaten des Hotels, Name = Ort des Hotels laut LiteAPI (`city` aus `GET /data/hotel`, wird nicht in der Reservierung gespeichert). Ohne Ort bleibt der Name. Ein Stop ohne Koordinaten bekommt nur die Koordinaten und behält seinen Namen.
  - Wegpunkte aus Vorschlägen (`SUGGESTION`) der Routen zum und vom Stop fallen weg, sie führen zum alten Ort. Wegpunkte des Nutzers (`USER`) bleiben. Beide Routen werden neu geplant (schnellster Weg), Incidents bewerten sie danach wie gewohnt.
  - Scheitert das Verschieben, bleibt die Buchung bestehen (nur Log).
- **Antwort:** `ReservationDto.replannedRouteIds` nennt die neu geplanten Routen, nur in der Antwort, die gebucht hat (nicht gespeichert). Bucht der Hintergrund-Job, ist die Liste leer; der Stop ist trotzdem verschoben.
- **Frontend:** Bei „Umgebucht“ steht „Der Stop liegt jetzt in …, die Route wurde neu geplant“. Liefern die Vorschläge für eine Route mehr als eine Option, gibt es den Knopf „Route … wählen“ zum bestehenden RouteSheet, sonst wird nichts gefragt.

## Route vor dem Buchen prüfen

- `GET /booking-reservations/{id}/route-check`, aufgerufen im Zahlschritt (nach dem Prebook, vor PayPal), berechnet und nicht gespeichert (`bookings.logic.StayRouteCheck`).
- Nur bei anderem Ort: bester Weg von der Abfahrt am vorigen Stop zum Hotel (`recommendations.Suggestions.towards`: ohne Wegpunkte, ohne Referenz, Incidents wie bei der Planung). Ergebnis: Ankunft inkl. Verzögerung, spätester Check-in (`checkInUntil` des Hotels am Anreisetag in der Zeitzone des Hotels, Mitternacht gilt als unbekannt), `late`, `blocked` (auch der beste Weg führt durch eine Sperrung).
- Ohne Prüfung (`problem`): erster Stop (`FIRST_STOP`), Abfahrt schon vorbei (`UNDER_WAY`, z. B. vor Ort), fehlende Abfahrt/Koordinaten, kein Weg, kein Graph. Dann zeigt die App nur den Hinweis auf den Ortswechsel.
- Nur ein Hinweis im Zahlschritt („Anderer Ort“, Ankunft, Warnung), Buchen bleibt möglich. Geprüft wird nur die gewählte Unterkunft, nicht die ganze Ergebnisliste (Routing je Kandidat wäre zu langsam).
- Neue Abhängigkeit `bookings --> recommendations` (kein Zyklus, `recommendations` kennt `bookings` nicht).

## Offene Punkte

- Live-Key und Freischaltung bei Nuitee, Karte im LiteAPI-Konto für `ACC_CREDIT_CARD`.
- Höhe der Provision, PayPal-Gebühren (wer trägt sie, in die Provision einrechnen), Provision bei Storno.
- Max. Preis: Gesamtpreis oder pro Nacht, Aufschlag auf den bisherigen Preis als Standard (z. B. +20 %)? Bisherige Buchung schon bezahlt, daher ist der Preis der Alternative zusätzlich.
- Kinder und Alter, `guestNationality` (Profil oder Gerät), Sprache der Hotel-Daten.
- Status der nicht nutzbaren Buchung (`CANCELLED` oder eigener Status) und ob sie in der Stop-Liste sichtbar bleibt.
- Webhooks von LiteAPI für Statusänderungen prüfen; bis dahin Abgleich per `GET /bookings/{id}` beim Öffnen.
- Weitere Use-Cases: anderer Ort (Region aus anderem Stop), Empfehlung bei nicht rechtzeitig fahrbarer Route (Modul `advisor`, Schnittstelle `StayOptions`, siehe [advisor.md](advisor.md#später-unterkünfte-und-nachricht-an-die-unterkunft)), Bezug zu Incidents an Unterkünften.
