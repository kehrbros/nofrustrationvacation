# Agents

- **Agent** = LLM-Zugang eines Providers (eigener API-Key bei einem Anbieter), über den verkaufte Kapazität bedient wird. Modul `com.vacantagent.agent`.
- **Felder:** `name`, `modelProvider` (klein geschrieben, gleiche Werte wie `modelProvider` von Angeboten, z. B. `openai`), `baseUrl` (optional, nur `https`, für eigene OpenAI-kompatible Endpunkte), `apiKeyEncrypted`, `apiKeyHint` (letzte 4 Zeichen zur Wiedererkennung), `createdAt`. Auditiert, außer dem verschlüsselten Key.
- **API:** `GET/POST /api/v1/agents`, `PUT /api/v1/agents/{id}/api-key`, `DELETE /api/v1/agents/{id}`. Der Key wird nie zurückgegeben. Fremde Agents → 404.

## API-Keys

- Verschlüsselt mit AES-256-GCM (`common.crypto.SecretCipher`), zufälliger IV je Wert, Format `v1:Base64(IV + Ciphertext + Tag)`.
- Schlüssel aus `VACANTAGENT_ENCRYPTION_KEY` (`vacantagent.secrets.encryption-key`, 32 Byte Base64, z. B. `openssl rand -base64 32`). Nie committen, wie die PayPal-Zugangsdaten.
- Ohne Schlüssel: temporärer Zufallsschlüssel beim Start (Warnung im Log). Passt zur In-Memory-H2; mit persistenter DB wären gespeicherte Keys nach einem Neustart unlesbar.
- Klartext nur über `AgentLogic.apiKey(agentId)` für den LLM-Proxy, nie loggen.

## Offen

- Key beim Hinterlegen beim Anbieter prüfen (z. B. `GET /models`).
- Angebote einem Agent zuordnen, Modell des Angebots gegen den Anbieter des Agents prüfen.
- Schlüsselrotation (Präfix `v1:` erlaubt ein späteres `v2:`).
