# Deployment (Docker Swarm und lokal)

## Lokal mit Docker Compose

- **Datei:** `docker-compose.local.yml`, Services `postgres`, `backend`, `frontend`. Alle Images werden aus den Dockerfiles im Repo gebaut, kein Zugriff auf die Container Registry und kein lokales Java/Maven/Node nötig. Das Backend baut `backend/Dockerfile.local` aus den Quellen (Multi-Stage mit Maven, Kontext über `Dockerfile.local.dockerignore`), CI nutzt weiter `backend/Dockerfile` mit dem vorher gebauten Jar.
- **Secrets:** lokal in der `.env` im Repo-Root (in `.gitignore`, nie committen). Vorlage `.env.example` kopieren: `POSTGRES_PASSWORD` ist Pflicht, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` und `VACANTAGENT_IDP_SIGNING_KEY` optional. Docker Compose liest die `.env` automatisch.
- **Start:** `docker compose -f docker-compose.local.yml up --build`. Frontend http://localhost:5173 (Port des Vite-Dev-Servers, damit Redirect-URIs und PayPal-Return-URL passen, also nicht parallel zu `npm run dev`), Backend http://localhost:8080, Login `test`/`test`.
- **Datenbank:** PostgreSQL 17 mit PostGIS wie im Stack, Daten im Volume `postgres-data`, Port `LOCAL_POSTGRES_PORT` (Standard 5432) für DB-Tools. Zurücksetzen: `docker compose -f docker-compose.local.yml down -v`. Das Passwort gilt nur beim ersten Anlegen des Volumes.
- Ohne Docker Compose: `mvn spring-boot:test-run` startet die App mit einem PostGIS-Container (Testcontainers, Docker nötig, Daten flüchtig), siehe README.

## Docker Swarm

- **Stack:** `docker-compose.yml` im Repo-Root, Services `postgres` (PostgreSQL 17 mit PostGIS), `backend` (Spring Boot, Port 8080) und `frontend` (nginx mit dem Vite-Build, Port 80) im Overlay-Netz `vacantagent`.
- **Images:** CI baut und pusht `ghcr.io/kehrbros/vacantagent/backend:main` (Branch `main`) und `ghcr.io/kehrbros/vacantagent/frontend:dev` (Branch `dev`). Andere Tags über `BACKEND_IMAGE`/`FRONTEND_IMAGE`. Lokal bauen: `cd backend && mvn -DskipTests verify`, danach im Root `docker compose build`. `docker stack deploy` ignoriert `build` und braucht die Images auf jedem Node (Registry oder lokal bei Single-Node-Swarm).
- **Datenbank:** PostgreSQL mit PostGIS (`postgis/postgis:17-3.5-alpine`), Host/DB/User über `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`.
- **Postgres-Service:** Daten im Volume `postgres-data`. Das Volume ist lokal auf einem Node, deshalb Placement `node.role == manager`. Bei mehreren Managern auf ein Node-Label umstellen (`docker node update --label-add vacantagent.db=true <node>`, Constraint `node.labels.vacantagent.db == true`).
- **Backend:** 1 Replica, Scheduler (Wechselkurse, PayPal-Status) und die Zuordnung von Incidents laufen im Prozess. Signaturschlüssel (Secret) und Autorisierungen des IdP (Datenbank) überleben einen Neustart, Logins und MCP-Verbindungen bleiben gültig. Swarm kennt kein `depends_on`, das Backend startet neu, bis Postgres erreichbar ist.
- **Streckennetz** (geplant): Image `postgis/postgis` statt `postgres`, Volume für `/data/osm` und `/data/graphs`, mehr Speicher fürs Backend (NZ-Import einige GB, Graph Memory-Mapped außerhalb des Heaps, `-XX:MaxRAMPercentage`). Konfiguration je Region siehe [network.md](network.md#konfiguration).
- **Frontend:** 2 Replicas, Rolling Update `start-first`. nginx leitet `/api/**` an `http://backend:8080` weiter (`BACKEND_UPSTREAM`, Template `frontend/nginx.conf`), der Browser spricht die API also same-origin an. Login (OIDC) geht direkt an `BACKEND_URL`.

## Secrets

- Docker Secrets statt Umgebungsvariablen, nie im Repo oder in `.env`:

  | Docker Secret | Datei im Container | Property |
  |---|---|---|
  | `vacantagent_paypal_client_id` | `/run/secrets/PAYPAL_CLIENT_ID` | `vacantagent.paypal.client-id` |
  | `vacantagent_paypal_client_secret` | `/run/secrets/PAYPAL_CLIENT_SECRET` | `vacantagent.paypal.client-secret` |
  | `vacantagent_idp_signing_key` | `/run/secrets/VACANTAGENT_IDP_SIGNING_KEY` | `vacantagent.auth.signing-key` |
  | `vacantagent_postgres_password` | `/run/secrets/POSTGRES_PASSWORD` (Backend), `/run/secrets/postgres_password` (Postgres, `POSTGRES_PASSWORD_FILE`) | `spring.datasource.password` |

- **Wie das Backend sie liest:** `spring.config.import=optional:configtree:/run/secrets/` in `application.properties`. Jede Datei wird zur Property mit dem Dateinamen als Schlüssel (abschließender Zeilenumbruch wird entfernt), die vorhandenen Platzhalter `${PAYPAL_CLIENT_ID:}` usw. lösen darauf auf. Ohne `/run/secrets` (lokal) greifen wie bisher Umgebungsvariablen bzw. `application-local.properties`, siehe [paypal.md](paypal.md).
- **Anlegen** (vor dem ersten Deploy, alle vier sind Pflicht):

  ```sh
  printf '%s' '<client-id>'     | docker secret create vacantagent_paypal_client_id -
  printf '%s' '<client-secret>' | docker secret create vacantagent_paypal_client_secret -
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | docker secret create vacantagent_idp_signing_key -
  openssl rand -base64 24 | tr -d '\n' | docker secret create vacantagent_postgres_password -
  ```

- **Rotieren:** Secrets sind unveränderlich. Neues Secret mit neuem Namen anlegen, `name:` in `docker-compose.yml` anpassen, neu deployen. Ein neuer Signaturschlüssel macht alle ausgegebenen Tokens ungültig: Nutzer melden sich neu an, MCP-Clients verbinden sich neu. Das Postgres-Passwort wirkt nur bei der Initialisierung des leeren Volumes, danach zusätzlich per `ALTER USER vacantagent PASSWORD '...'` ändern.

## Konfiguration

- Variablen siehe Abschnitt „Swarm stack“ in `.env.example`. `docker stack deploy` liest keine `.env`, daher vorher in die Shell laden: `set -a; . ./.env; set +a`. Die lokalen Secrets aus der `.env` nutzt der Stack nicht.
- `BACKEND_URL` / `FRONTEND_URL`: öffentliche URLs aus Sicht des Browsers. Daraus leitet das Backend Issuer, Redirect-URIs, CORS und PayPal-Return-URLs ab (`vacantagent.backend-url`, `vacantagent.frontend-url`). Das Backend ruft `BACKEND_URL` selbst auf (Issuer-Discovery), die URL muss also auch aus dem Container erreichbar sein.
- `VITE_OIDC_AUTHORITY` ist Build-Zeit: Das CI-Image enthält `http://localhost:8080`. Für eine andere `BACKEND_URL` das Frontend-Image mit `docker compose build frontend` (übernimmt `BACKEND_URL`) neu bauen.
- MCP-Clients (Claude, ChatGPT) erreichen den Server unter `${BACKEND_URL}/mcp`. Dafür muss `BACKEND_URL` HTTPS sein und aus dem Internet erreichbar (z. B. TLS am Reverse Proxy). Das Backend braucht ausgehendes HTTPS (Port 443), um die Client ID Metadata Documents der Clients abzurufen, siehe [authentication.md](authentication.md#mcp-clients-cimd).
- PayPal: `PAYPAL_ENVIRONMENT` (`sandbox`/`live`). In der PayPal-App muss die Return URL `${FRONTEND_URL}/paypal/connected` eingetragen sein.
- Deploy: `docker stack deploy -c docker-compose.yml vacantagent`, Status `docker stack ps vacantagent`, Logs `docker service logs -f vacantagent_backend`.

## Offen

- Backups des Postgres-Volumes.
- TLS/Reverse Proxy (z. B. Traefik) vor dem Stack, Healthchecks über Spring Boot Actuator.
