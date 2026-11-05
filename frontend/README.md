# Frontend

React + TypeScript mit Vite. Ant Design und AG Grid sind installiert und können bei Bedarf in Komponenten eingebunden werden.

```sh
npm install
npm run dev
npm test
npm run build
```

## OpenAPI-Client

Das Spring-Boot-Projekt liegt unter `../backend`. Die Generierung liest die eingecheckte Spezifikation `openapi.json`. Sie wird durch `mvn verify` in `../backend` neu geschrieben und muss bei API-Änderungen mit eingecheckt werden. Die Backend-CI schlägt fehl, wenn sie nicht zum Code passt:

```sh
npm run generate:api
```

Für einen anderen Dateipfad oder eine URL `OPENAPI_SPEC` setzen (unter PowerShell zum Beispiel `$env:OPENAPI_SPEC = 'http://localhost:18180/v3/api-docs'`). Die generierten Typen und Fetch-Clients landen unter `src/generated/api` und werden nicht eingecheckt. Vor einem Build, der sie importiert, muss die Generierung ausgeführt werden. Der Vite-Entwicklungsserver leitet `/api` an `http://localhost:18180` weiter.

## CI und Docker

GitHub Actions generiert den API-Client aus `openapi.json` und führt `npm test` für Pushes auf Branches und für Pull Requests aus. Nach einem erfolgreichen Test bei einem Push auf `dev` baut die Pipeline ein Nginx-Image und veröffentlicht es als `ghcr.io/<owner>/<repo>/frontend:dev` sowie mit einem `sha-<commit>`-Tag. Der Docker-Build generiert den API-Client ebenfalls aus `openapi.json`. Das Image enthält die gebaute statische App. Das Routing von `/api` zum Backend muss in der späteren Laufzeitumgebung eingerichtet werden.
