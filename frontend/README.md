# App (React Native)

Android- und iOS-App mit [Expo](https://docs.expo.dev/) (SDK 58, React Native 0.88) und Expo Router, TypeScript. Architektur und Entscheidungen siehe [Docs/architecture.md](../Docs/architecture.md#app-frontend), Login siehe [Docs/authentication.md](../Docs/authentication.md#app).

```sh
npm install
npm run generate:api   # API-Client aus openapi.json, vor typecheck/test/start
npm run android        # Development Build bauen und im Emulator/Gerät starten (Android SDK nötig)
npm run ios            # dito für iOS (nur macOS mit Xcode)
npm start              # nur Metro starten, wenn der Development Build schon installiert ist
npm run typecheck
npm test
```

## Development Build statt Expo Go

- Die App nutzt eine eigene Redirect-URI `vacantagent://auth/callback`. Expo Go hat ein eigenes Schema (`exp://…`), deshalb funktioniert der Login nur im Development Build (`expo-dev-client`).
- Ohne lokales Android Studio/Xcode (z. B. iOS unter Windows): `npx eas-cli@latest build --profile development --platform ios|android` baut in der Cloud (Expo-Konto nötig), Profile in `eas.json`.
- `android/` und `ios/` werden generiert (Continuous Native Generation) und nicht eingecheckt. Native Einstellungen gehören in `app.json` bzw. Config-Plugins.

## Backend-Verbindung

- `EXPO_PUBLIC_BACKEND_URL` (Vorlage `.env.example` → `.env.local`): Backend und Issuer des IdP, muss exakt `VACANTAGENT_BACKEND_URL` des Backends entsprechen (Tokens werden gegen den Issuer geprüft). Standard `http://localhost:8080`.
- Android-Emulator: Standard beibehalten und `adb reverse tcp:8080 tcp:8080` ausführen. iOS-Simulator: `localhost` funktioniert direkt.
- Gerät im WLAN: `EXPO_PUBLIC_BACKEND_URL=http://<IP des PCs>:8080` und Backend mit `VACANTAGENT_BACKEND_URL` auf dieselbe URL starten. Außerhalb der lokalen Entwicklung nur HTTPS.
- Login lokal mit `test` / `test`.

## Aufbau

- `src/app/`: Routen (Expo Router), jede Datei ein Screen, `_layout.tsx` sind Navigatoren. `(app)/` nur mit Login (`Stack.Protected` in `src/app/_layout.tsx`), `login.tsx` nur ohne.
- `src/auth/`: OIDC-Login (`oidc.ts`, `expo-auth-session`), `Session` mit Token-Refresh, Tokens im Secure Store (`tokenStorage.ts`), `AuthProvider`/`useAuth`.
- `src/api/apiClient.ts`: konfiguriert den generierten Client (Backend-URL, Bearer-Token, 401 beendet die Session).
- `src/theme/`: Design-Tokens (Farben, Schrift Outfit, Abstände, Radien), `src/components/`: Basiskomponenten `Screen`, `AppText`, `Button`.
- Tests mit Jest (`jest-expo`) und React Native Testing Library neben dem Code (`*.test.ts(x)`).

## OpenAPI-Client

- `openapi.json` wird von `mvn verify` in `../backend` geschrieben und eingecheckt, die Backend-CI prüft, dass sie zum Code passt.
- `npm run generate:api` erzeugt Typen und Fetch-Funktionen unter `src/generated/api` (nicht eingecheckt). Andere Quelle über `OPENAPI_SPEC`, z. B. `http://localhost:8080/v3/api-docs`.

## CI

- `.github/workflows/frontend.yml`: `npm ci`, API-Client generieren, `typecheck`, `test` und ein JS-Bundle für Android und iOS (`expo export`) bei jedem Push und Pull Request. Native Builds und Store-Veröffentlichung (EAS) sind noch nicht eingerichtet.
