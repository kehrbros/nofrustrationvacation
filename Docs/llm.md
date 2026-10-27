# LLM-Proxy

- **Modul `com.vacantagent.llm`:** schickt Prompts eines Consumers über eine seiner Vereinbarungen an den Agent des Providers und rechnet die Nutzung ab (noch ohne Endpunkt oder MCP-Tool).

## Wo was liegt

- **`LlmLogic`:** nur fachliche Prüfungen als `boolean`: `isPromptValid`, `hasPositiveCredit`.
- **Entities:** Regeln auf eigenen Feldern: `ProviderOffer.hasTokensLeft()` (`null` = unbegrenzt), `Agreement.costFor(promptTokens, completionTokens)`.
- **Markt / Abrechnung:** aktive Vereinbarung als DB-Filter (`findActiveAgreement`), atomares Token-Update (`recordOfferSuccess`), Buchung (`UsageBilling.settle`) bleiben in ihren Modulen.
- **`LlmProxyService`:** der Ablauf: Reihenfolge, Transaktionen, Protokoll, Fehlerbehandlung, Wiedereintritt in den Markt. Keine eigene fachliche Bedingung, jede kommt aus `LlmLogic` oder einer Entity.

## Ablauf `LlmProxyService.prompt(appUserId, prompt, modelProvider?, modelName?, orderLimits?)`

1. Prompt muss gesetzt und `CREDIT` des Consumers positiv sein, sonst `LlmRequestException`.
2. Aktive Vereinbarung des Consumers suchen (`IMarketLogic.findActiveAgreement`): gültig, nicht idle, Angebot hat noch Tokens (oder kein Limit). `modelProvider`/`modelName` filtern nur, wenn angegeben; ohne beide passt jede. Neueste zuerst.
   - Keine und `orderLimits` (`OrderLimits`: Höchstpreise, Wartezeit) gesetzt: Order für `modelProvider`/`modelName` mit diesen Limits per `acceptOrderAndAwaitMatch`, warten bis Wartezeit + 10 s. `Matched` → die neue Vereinbarung erneut per `findActiveAgreement` laden; sonst `LlmRequestException` mit dem Grund.
   - Keine und ohne `orderLimits` → `LlmRequestException`.
3. Agent des Angebots laden, Prompt über Spring AI senden (`ChatModelFactory`: OpenAI-kompatible API an `baseUrl` des Agents, sonst OpenAI-Standard, Modell = `name` des Agents). Der Aufruf läuft außerhalb jeder Transaktion.
4. **Erfolg**, in einer Transaktion:
   - `LlmProtocol` mit Tokens aus `ChatResponse.getMetadata().getUsage()`, Start, Ende, Agent, Vereinbarung.
   - Angebot: `availableTokens -= totalTokens` (nie unter 0, `null` = unbegrenzt bleibt), `lastSuccessAt`; ein atomares `UPDATE`, da mehrere Prompts dasselbe Angebot nutzen können.
   - `recordAgreementSuccess`, damit die Vereinbarung nicht idle wird.
   - Kosten (`Agreement.costFor`) = Eingabepreis × Prompt-Tokens / 1 Mio + Ausgabepreis × Completion-Tokens / 1 Mio (Preise der Vereinbarung), `UsageBilling.settle`. `CREDIT` darf dabei negativ werden, siehe [accounting.md](accounting.md).
   - Danach, wenn Tokens übrig: Angebot per `resubmitOffer` zurück in den Markt. Liegt es dort schon, lehnt die Engine das Duplikat ab.
5. **Fehler:** `LlmProtocol` mit `success = false` und 0 Tokens, `lastErrorAt` am Angebot, keine Buchung, `LlmCallFailedException`.

## LlmProtocol

- Tabelle `llm_protocol` (`V15__llm_protocol.sql`): `agent_id`, `agreement_id`, `started_at`, `ended_at`, `prompt_tokens`, `completion_tokens`, `total_tokens`, `success`. Nur anhängen, nicht auditiert.

## MCP-Tool `prompt`

- `LlmTools` unter `/mcp` (JWT wie `/api/**`): `prompt(prompt, modelProvider?, modelName?, maxCostPerMillionInputTokens?, maxCostPerMillionOutputTokens?, currency?, marketWaitTime?)` ruft `LlmProxyService.prompt` für den angemeldeten Nutzer. Die Höchstpreise und `currency` nur gemeinsam; mit ihnen wird ohne passende Vereinbarung eine Order platziert. `marketWaitTime` Standard `PT30S`, höchstens `PT60S`.
- Antwort `PromptResultDto`: `text`, `agreementId`, `promptTokens`, `completionTokens`, `totalTokens`, `cost` als `MoneyDto` in der bevorzugten Währung des Nutzers (`MoneyRounding.PRICE`, 6 Nachkommastellen).
- Ablehnungen (`LlmRequestException`) und Fehler des Providers (`LlmCallFailedException`) kommen als Tool-Fehler mit Meldung zurück.

## Offen

- REST-Endpunkt für Prompts (`LlmProxyController` ist noch ein Platzhalter).
- Nur OpenAI-kompatible APIs; ein Agent mit `modelProvider` z. B. `anthropic` ohne `baseUrl` landet bei OpenAI.
- Ein Angebot mit aktiver Vereinbarung geht nach jedem Prompt wieder in den Markt und kann so mehrere Vereinbarungen bedienen. Nach einem Neustart lädt `ProviderOfferRepository.findAllAvailable` solche Angebote aber noch nicht (siehe TODO dort).
- Usage fehlt in der Antwort → 0 Tokens, kostenlos.

## Tests

- `LlmProxyServiceTest`: Erfolg mit Abrechnung und Wiedereintritt in den Markt, Guthaben ins Minus, Tokens aufgebraucht, Fehler, Modellfilter, kein Guthaben, keine Vereinbarung, Order bei fehlender Vereinbarung (passt / passt nicht). Chat-Client gemockt, Markt, Konten und DB echt.
- `LlmToolsTest`: MCP-Tool mit gemocktem `LlmProxyService` (Antwort, Kosten in USD, Filter, Ablehnung, Anmeldung nötig).
- `LlmLogicTest`, `AgreementTest`, `ProviderOfferTest`: die Prüfungen und Berechnungen als Unit-Tests ohne Spring.