# Deployment (Docker Swarm)

- **Stack:** `docker-compose.yml` im Repo-Root, Services `postgres` (PostgreSQL 17), `backend` (Spring Boot, Port 8080) und `frontend` (nginx mit dem Vite-Build, Port 80) im Overlay-Netz `vacantagent`.
- **Images:** CI baut und pusht `ghcr.io/kehrbros/vacantagent/backend:main` (Branch `main`) und `ghcr.io/kehrbros/vacantagent/frontend:dev` (Branch `dev`). Andere Tags über `BACKEND_IMAGE`/`FRONTEND_IMAGE`. Lokal bauen: `cd backend && mvn -DskipTests verify`, danach im Root `docker compose build`. `docker stack deploy` ignoriert `build` und braucht die Images auf jedem Node (Registry oder lokal bei Single-Node-Swarm).
- **Datenbank:** Im Stack PostgreSQL über das Profil `postgres` (`SPRING_PROFILES_ACTIVE=postgres`, `application-postgres.properties`, Host/DB/User über `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`). Ohne Profil (lokal) weiter In-Memory-H2. Flyway-Migrationen laufen auf beiden, daher nur Standard-SQL verwenden (z. B. `SMALLINT` statt `TINYINT`).
- **Postgres-Service:** Daten im Volume `postgres-data`. Das Volume ist lokal auf einem Node, deshalb Placement `node.role == manager`. Bei mehreren Managern auf ein Node-Label umstellen (`docker node update --label-add vacantagent.db=true <node>`, Constraint `node.labels.vacantagent.db == true`).
- **Backend:** 1 Replica, Matching-Engine und Signaturschlüssel des IdP liegen im Prozess. Nach einem Neustart sind alle Logins ungültig. Swarm kennt kein `depends_on`, das Backend startet neu, bis Postgres erreichbar ist.
- **Frontend:** 2 Replicas, Rolling Update `start-first`. nginx leitet `/api/**` an `http://backend:8080` weiter (`BACKEND_UPSTREAM`, Template `frontend/nginx.conf`), der Browser spricht die API also same-origin an. Login (OIDC) geht direkt an `BACKEND_URL`.

## Secrets

- Docker Secrets statt Umgebungsvariablen, nie im Repo oder in `.env`:

  | Docker Secret | Datei im Container | Property |
  |---|---|---|
  | `vacantagent_paypal_client_id` | `/run/secrets/PAYPAL_CLIENT_ID` | `vacantagent.paypal.client-id` |
  | `vacantagent_paypal_client_secret` | `/run/secrets/PAYPAL_CLIENT_SECRET` | `vacantagent.paypal.client-secret` |
  | `vacantagent_encryption_key` | `/run/secrets/VACANTAGENT_ENCRYPTION_KEY` | `vacantagent.secrets.encryption-key` |
  | `vacantagent_postgres_password` | `/run/secrets/POSTGRES_PASSWORD` (Backend), `/run/secrets/postgres_password` (Postgres, `POSTGRES_PASSWORD_FILE`) | `spring.datasource.password` |

- **Wie das Backend sie liest:** `spring.config.import=optional:configtree:/run/secrets/` in `application.properties`. Jede Datei wird zur Property mit dem Dateinamen als Schlüssel (abschließender Zeilenumbruch wird entfernt), die vorhandenen Platzhalter `${PAYPAL_CLIENT_ID:}` usw. lösen darauf auf. Ohne `/run/secrets` (lokal) greifen wie bisher Umgebungsvariablen bzw. `application-local.properties`, siehe [paypal.md](paypal.md).
- **Anlegen** (vor dem ersten Deploy, alle vier sind Pflicht):

  ```sh
  printf '%s' '<client-id>'     | docker secret create vacantagent_paypal_client_id -
  printf '%s' '<client-secret>' | docker secret create vacantagent_paypal_client_secret -
  openssl rand -base64 32 | tr -d '\n' | docker secret create vacantagent_encryption_key -
  openssl rand -base64 24 | tr -d '\n' | docker secret create vacantagent_postgres_password -
  ```

- **Rotieren:** Secrets sind unveränderlich. Neues Secret mit neuem Namen anlegen, `name:` in `docker-compose.yml` anpassen, neu deployen. Der Verschlüsselungsschlüssel darf nicht rotiert werden, sonst sind gespeicherte API-Keys der Agents unlesbar. Das Postgres-Passwort wirkt nur bei der Initialisierung des leeren Volumes, danach zusätzlich per `ALTER USER vacantagent PASSWORD '...'` ändern.

## Konfiguration

- Variablen siehe `.env.example`. `docker stack deploy` liest keine `.env`, daher vorher in die Shell laden: `set -a; . ./.env; set +a`.
- `BACKEND_URL` / `FRONTEND_URL`: öffentliche URLs aus Sicht des Browsers. Daraus leitet das Backend Issuer, Redirect-URIs, CORS und PayPal-Return-URLs ab (`vacantagent.backend-url`, `vacantagent.frontend-url`). Das Backend ruft `BACKEND_URL` selbst auf (Issuer-Discovery), die URL muss also auch aus dem Container erreichbar sein.
- `VITE_OIDC_AUTHORITY` ist Build-Zeit: Das CI-Image enthält `http://localhost:8080`. Für eine andere `BACKEND_URL` das Frontend-Image mit `docker compose build frontend` (übernimmt `BACKEND_URL`) neu bauen.
- PayPal: `PAYPAL_ENVIRONMENT` (`sandbox`/`live`). In der PayPal-App muss die Return URL `${FRONTEND_URL}/paypal/connected` eingetragen sein.
- Deploy: `docker stack deploy -c docker-compose.yml vacantagent`, Status `docker stack ps vacantagent`, Logs `docker service logs -f vacantagent_backend`.

## Offen

- Persistenter Signaturschlüssel des IdP, Backups des Postgres-Volumes.
- TLS/Reverse Proxy (z. B. Traefik) vor dem Stack, Healthchecks über Spring Boot Actuator.
