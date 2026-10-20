# Frontend

React + TypeScript mit Vite. Ant Design und AG Grid sind installiert und können bei Bedarf in Komponenten eingebunden werden.

```sh
npm install
npm run dev
npm test
npm run build
```

## OpenAPI-Client

Das Spring-Boot-Projekt liegt unter `../backend`. Standardmäßig erwartet die Generierung dessen exportierte Spezifikation unter `../backend/target/openapi.json`:

```sh
npm run generate:api
```

Für einen anderen Dateipfad oder eine URL `OPENAPI_SPEC` setzen (unter PowerShell zum Beispiel `$env:OPENAPI_SPEC = '../backend/target/custom-openapi.json'`). Die generierten Typen und Fetch-Clients landen unter `src/generated/api` und werden nicht eingecheckt. Vor einem Build, der sie importiert, muss die Generierung ausgeführt werden. Der Vite-Entwicklungsserver leitet `/api` an `http://localhost:8080` weiter.

## CI und Docker

GitHub Actions führt `npm test` für Pushes auf Branches und für Pull Requests aus. Nach einem erfolgreichen Test bei einem Push auf `dev` baut die Pipeline ein Nginx-Image und veröffentlicht es als `ghcr.io/<owner>/<repo>/frontend:dev` sowie mit einem `sha-<commit>`-Tag. Das Image enthält die gebaute statische App. Das Routing von `/api` zum Backend muss in der späteren Laufzeitumgebung eingerichtet werden.
