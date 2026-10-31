# Deployment (Docker Swarm und lokal)

## Lokal mit Docker Compose

- **Datei:** `docker-compose.local.yml`, Services `postgres` und `backend`. Alle Images werden aus den Dockerfiles im Repo gebaut, kein Zugriff auf die Container Registry und kein lokales Java/Maven nötig. Die App läuft im Emulator oder auf dem Gerät und spricht `http://localhost:8080` an, siehe [frontend/README.md](../frontend/README.md). Das Backend baut `backend/Dockerfile.local` aus den Quellen (Multi-Stage mit Maven, Kontext über `Dockerfile.local.dockerignore`), CI nutzt weiter `backend/Dockerfile` mit dem vorher gebauten Jar.
- **Secrets:** lokal in der `.env` im Repo-Root (in `.gitignore`, nie committen). Vorlage `.env.example` kopieren: `POSTGRES_PASSWORD` ist Pflicht, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` und `VACANTAGENT_IDP_SIGNING_KEY` optional. Docker Compose liest die `.env` automatisch.
- **Start:** `docker compose -f docker-compose.local.yml up --build`. Backend http://localhost:8080, Login `test`/`test`.
- **Datenbank:** PostgreSQL 17 mit Profil `postgres` wie im Stack, Daten im Volume `postgres-data`, Port `LOCAL_POSTGRES_PORT` (Standard 5432) für DB-Tools. Zurücksetzen: `docker compose -f docker-compose.local.yml down -v`. Das Passwort gilt nur beim ersten Anlegen des Volumes.
- Ohne Docker weiter `mvn spring-boot:run` mit In-Memory-H2, siehe README.

## Docker Swarm

- **Stack:** `docker-compose.yml` im Repo-Root, Services `postgres` (PostgreSQL 17) und `backend` (Spring Boot, Port 8080) im Overlay-Netz `vacantagent`. Ein Web-Frontend gibt es nicht mehr, die App wird über die Stores bzw. EAS verteilt und spricht `BACKEND_URL` direkt an.
- **Images:** CI baut und pusht `ghcr.io/kehrbros/vacantagent/backend:main` (Branch `main`). Andere Tags über `BACKEND_IMAGE`. Lokal bauen: `cd backend && mvn -DskipTests verify`, danach im Root `docker compose build`. `docker stack deploy` ignoriert `build` und braucht die Images auf jedem Node (Registry oder lokal bei Single-Node-Swarm).
- **Datenbank:** Im Stack PostgreSQL über das Profil `postgres` (`SPRING_PROFILES_ACTIVE=postgres`, `application-postgres.properties`, Host/DB/User über `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`). Ohne Profil (lokal) weiter In-Memory-H2. Flyway-Migrationen laufen auf beiden, daher nur Standard-SQL verwenden (z. B. `SMALLINT` statt `TINYINT`).
- **Postgres-Service:** Daten im Volume `postgres-data`. Das Volume ist lokal auf einem Node, deshalb Placement `node.role == manager`. Bei mehreren Managern auf ein Node-Label umstellen (`docker node update --label-add vacantagent.db=true <node>`, Constraint `node.labels.vacantagent.db == true`).
- **Backend:** 1 Replica, Scheduler (Wechselkurse, PayPal-Status) und die Zuordnung von Incidents laufen im Prozess. Signaturschlüssel (Secret) und Autorisierungen des IdP (Datenbank) überleben einen Neustart, Logins und MCP-Verbindungen bleiben gültig. Swarm kennt kein `depends_on`, das Backend startet neu, bis Postgres erreichbar ist.

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
- `BACKEND_URL`: öffentliche URL des Backends (`vacantagent.backend-url`), Issuer des IdP und Basis-URL der App (`EXPO_PUBLIC_BACKEND_URL` beim App-Build). Für die App außerhalb der Entwicklung HTTPS. `FRONTEND_URL` (`vacantagent.frontend-url`) bestimmt nur noch die PayPal-Return-URLs (Altbestand) und CORS. Das Backend ruft `BACKEND_URL` selbst auf (Issuer-Discovery), die URL muss also auch aus dem Container erreichbar sein.
- MCP-Clients (Claude, ChatGPT) erreichen den Server unter `${BACKEND_URL}/mcp`. Dafür muss `BACKEND_URL` HTTPS sein und aus dem Internet erreichbar (z. B. TLS am Reverse Proxy). Das Backend braucht ausgehendes HTTPS (Port 443), um die Client ID Metadata Documents der Clients abzurufen, siehe [authentication.md](authentication.md#mcp-clients-cimd).
- PayPal: `PAYPAL_ENVIRONMENT` (`sandbox`/`live`). In der PayPal-App muss die Return URL `${FRONTEND_URL}/paypal/connected` eingetragen sein.
- Deploy: `docker stack deploy -c docker-compose.yml vacantagent`, Status `docker stack ps vacantagent`, Logs `docker service logs -f vacantagent_backend`.

## Offen

- Backups des Postgres-Volumes.
- TLS/Reverse Proxy (z. B. Traefik) vor dem Stack, Healthchecks über Spring Boot Actuator.
