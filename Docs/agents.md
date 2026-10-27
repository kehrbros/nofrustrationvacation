# Agents

- **Agent** = LLM-Zugang eines Providers (eigener API-Key bei einem Anbieter), über den verkaufte Kapazität bedient wird. Modul `com.vacantagent.agent`.
- **Felder:** `name` (das Modell, das Angebote des Agents verkaufen, z. B. `gpt-5`), `modelProvider` (klein geschrieben, gleiche Werte wie `modelProvider` von Angeboten, z. B. `openai`), `baseUrl` (optional, nur `https`, für eigene OpenAI-kompatible Endpunkte), `apiKeyEncrypted`, `apiKeyHint` (letzte 4 Zeichen zur Wiedererkennung), `createdAt`. Auditiert, außer dem verschlüsselten Key.
- **API:** `GET/POST /api/v1/agents`, `PUT /api/v1/agents/{id}/api-key`, `DELETE /api/v1/agents/{id}`. Der Key wird nie zurückgegeben. Fremde Agents → 404. Agent mit Angeboten entfernen → 409.
- **Angebote:** `place_offer` nimmt eine `agentId` des eigenen Users. `ProviderOffer` hält nur die `agentId` (Fremdschlüssel in der DB, keine JPA-Beziehung zwischen den Modulen) und kopiert `modelProvider` und `name` des Agents (als `modelName`), weil das Matching ohne I/O läuft. Das Modell eines Agents ist nach dem Anlegen nicht änderbar, die Kopie veraltet also nicht.

## API-Keys

- Verschlüsselt mit AES-256-GCM (`common.crypto.SecretCipher`), zufälliger IV je Wert, Format `v1:Base64(IV + Ciphertext + Tag)`.
- Schlüssel aus `VACANTAGENT_ENCRYPTION_KEY` (`vacantagent.secrets.encryption-key`, 32 Byte Base64, z. B. `openssl rand -base64 32`). Nie committen, wie die PayPal-Zugangsdaten.
- Ohne Schlüssel: temporärer Zufallsschlüssel beim Start (Warnung im Log). Passt zur In-Memory-H2; mit persistenter DB wären gespeicherte Keys nach einem Neustart unlesbar.
- Klartext nur über `AgentLogic.apiKey(agentId)` für den LLM-Proxy (`LlmLogic`, siehe [llm.md](llm.md)), nie loggen.

## Offen

- Key beim Hinterlegen beim Anbieter prüfen (z. B. `GET /models`).
- Schlüsselrotation (Präfix `v1:` erlaubt ein späteres `v2:`).
