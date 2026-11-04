# Deployment (Docker Swarm und lokal)

## Lokal mit Docker Compose

- **Datei:** `docker-compose.local.yml`, Services `postgres`, `backend`, `frontend`. Alle Images werden aus den Dockerfiles im Repo gebaut, kein Zugriff auf die Container Registry und kein lokales Java/Maven/Node nötig. Das Backend baut `backend/Dockerfile.local` aus den Quellen (Multi-Stage mit Maven, Kontext über `Dockerfile.local.dockerignore`), CI nutzt weiter `backend/Dockerfile` mit dem vorher gebauten Jar.
- **Secrets:** lokal in der `.env` im Repo-Root (in `.gitignore`, nie committen). Vorlage `.env.example` kopieren: `POSTGRES_PASSWORD` ist Pflicht, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `NFV_IDP_SIGNING_KEY`, `OPENAI_API_KEY` und `LITEAPI_API_KEY` optional. Docker Compose liest die `.env` automatisch.
- **Start:** `docker compose -f docker-compose.local.yml up --build`. Frontend http://localhost:5173 (Port des Vite-Dev-Servers, damit Redirect-URIs und PayPal-Return-URL passen, also nicht parallel zu `npm run dev`), Backend http://localhost:8080, Login `test`/`test`.
- **Datenbank:** PostgreSQL 17 mit PostGIS wie im Stack, Daten im Volume `postgres-data`, Port `LOCAL_POSTGRES_PORT` (Standard 5432) für DB-Tools. Zurücksetzen: `docker compose -f docker-compose.local.yml down -v`. Das Passwort gilt nur beim ersten Anlegen des Volumes.
- **Streckennetz:** Bind-Mounts `~/data/osm` (nur lesend) und `~/data/graphs` nach `/data` (`NFV_DATA_DIR=/data`), Host-Pfad über `NFV_HOST_DATA_DIR`. Extrakt von Hand nach `~/data/osm/new-zealand-latest.osm.pbf`, siehe [network.md](network.md#konfiguration). Ohne Extrakt startet das Backend, Routing antwortet mit 503. `NFV_NETWORK_IMPORT_ON_START=false` in der `.env` überspringt den Import.
- Ohne Docker Compose: `mvn spring-boot:test-run` startet die App mit einem PostGIS-Container (Testcontainers, Docker nötig, Daten flüchtig), siehe README.

## Docker Swarm

- **Stack:** `docker-compose.yml` im Repo-Root, Services `postgres` (PostgreSQL 17 mit PostGIS), `backend` (Spring Boot, Port 8080), `frontend` (nginx mit dem Vite-Build, Port 80) und `caddy` (TLS-Proxy, Ports 80/443) im Overlay-Netz `nfv`. Nur Caddy veröffentlicht Ports, siehe [TLS (Caddy)](#tls-caddy).
- **Images:** CI baut und pusht bei jedem Push auf `main` `ghcr.io/kehrbros/vacantagent/backend`, `…/frontend` und `…/caddy` mit den Tags `main` und `sha-<commit>`, siehe [Continuous Deployment](#continuous-deployment). Andere Tags über `BACKEND_IMAGE`/`FRONTEND_IMAGE`/`CADDY_IMAGE`. Lokal bauen: `cd backend && mvn -DskipTests verify`, danach im Root `docker compose build`. `docker stack deploy` ignoriert `build` und braucht die Images auf jedem Node (Registry oder lokal bei Single-Node-Swarm).
- **Datenbank:** PostgreSQL mit PostGIS (`postgis/postgis:17-3.5-alpine`), Host/DB/User über `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`.
- **Postgres-Service:** Daten im Volume `postgres-data`. Das Volume ist lokal auf einem Node, deshalb Placement `node.role == manager`. Bei mehreren Managern auf ein Node-Label umstellen (`docker node update --label-add nfv.db=true <node>`, Constraint `node.labels.nfv.db == true`).
- **Backend:** 1 Replica, Scheduler (Wechselkurse, PayPal-Status) und die Zuordnung von Incidents laufen im Prozess. Signaturschlüssel (Secret) und Autorisierungen des IdP (Datenbank) überleben einen Neustart, Logins und MCP-Verbindungen bleiben gültig. Swarm kennt kein `depends_on`, das Backend startet neu, bis Postgres erreichbar ist.
- **Streckennetz:** Bind-Mounts `${NFV_HOST_DATA_DIR}/osm` (nur lesend) und `…/graphs` nach `/data` (`NFV_DATA_DIR=/data`). Extrakt nicht im Image und nicht im Repository, bis zum Geofabrik-Download von Hand nach `${NFV_HOST_DATA_DIR}/osm/new-zealand-latest.osm.pbf`, siehe [network.md](network.md#konfiguration).
  - `NFV_HOST_DATA_DIR` ist im Stack Pflicht (absoluter Pfad auf dem Server, z. B. `/home/deploy/data`). `docker stack config/deploy` kann keine verschachtelten Defaults wie `${A:-${HOME}/data}` auflösen, außerdem rendert die Pipeline die Datei auf dem CI-Runner.
  - Verzeichnisse vorher anlegen (`mkdir -p /home/deploy/data/osm /home/deploy/data/graphs`), Swarm startet den Container sonst nicht.
  - Bind-Mounts sind lokal auf einem Node, daher Backend mit Placement `node.role == manager` (wie Postgres).
  - Speicherlimit Backend 4G (NZ-Import einige GB, Graph Memory-Mapped außerhalb des Heaps, `-XX:MaxRAMPercentage=75`). Erster Start mit Import dauert einige Minuten, Routing antwortet bis dahin mit 503.
- **Frontend:** 2 Replicas, Rolling Update `start-first`. nginx leitet `/api/**` an `http://backend:8080` weiter (`BACKEND_UPSTREAM`, Template `frontend/nginx.conf`), im Stack routet Caddy `/api/**` direkt ans Backend. Login (OIDC) geht an `https://${DOMAIN}` (same-origin, siehe [TLS (Caddy)](#tls-caddy)).

## Secrets

- Docker Secrets statt Umgebungsvariablen, nie im Repo oder in `.env`:

  | Docker Secret | Datei im Container | Property |
  |---|---|---|
  | `nfv_paypal_client_id` | `/run/secrets/PAYPAL_CLIENT_ID` | `nfv.paypal.client-id` |
  | `nfv_paypal_client_secret` | `/run/secrets/PAYPAL_CLIENT_SECRET` | `nfv.paypal.client-secret` |
  | `nfv_idp_signing_key` | `/run/secrets/NFV_IDP_SIGNING_KEY` | `nfv.auth.signing-key` |
  | `nfv_openai_api_key` | `/run/secrets/OPENAI_API_KEY` | `nfv.assistant.api-key` (Auslesen von Buchungsbestätigungen, Titelfotos; leer: nur manuelle Eingabe, Platzhalterbild) |
  | `nfv_liteapi_api_key` | `/run/secrets/LITEAPI_API_KEY` | `nfv.bookings.alternatives.api-key` (Alternative Unterkünfte suchen und buchen; leer: Funktion aus) |
  | `nfv_postgres_password` | `/run/secrets/POSTGRES_PASSWORD` (Backend), `/run/secrets/postgres_password` (Postgres, `POSTGRES_PASSWORD_FILE`) | `spring.datasource.password` |

- **Wie das Backend sie liest:** `spring.config.import=optional:configtree:/run/secrets/` in `application.properties`. Jede Datei wird zur Property mit dem Dateinamen als Schlüssel (abschließender Zeilenumbruch wird entfernt), die vorhandenen Platzhalter `${PAYPAL_CLIENT_ID:}` usw. lösen darauf auf. Ohne `/run/secrets` (lokal) greifen wie bisher Umgebungsvariablen bzw. `application-local.properties`, siehe [paypal.md](paypal.md).
- **Anlegen** (vor dem ersten Deploy, alle sechs sind Pflicht):

  ```sh
  printf '%s' '<client-id>'     | docker secret create nfv_paypal_client_id -
  printf '%s' '<client-secret>' | docker secret create nfv_paypal_client_secret -
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | docker secret create nfv_idp_signing_key -
  openssl rand -base64 24 | tr -d '\n' | docker secret create nfv_postgres_password -
  printf '%s' '<openai-api-key>' | docker secret create nfv_openai_api_key -   # leer anlegen, wenn kein Key
  printf '%s' '<liteapi-api-key>' | docker secret create nfv_liteapi_api_key -   # leer anlegen, wenn kein Key
  ```

- **Rotieren:** Secrets sind unveränderlich. Neues Secret mit neuem Namen anlegen, `name:` in `docker-compose.yml` anpassen, neu deployen. Ein neuer Signaturschlüssel macht alle ausgegebenen Tokens ungültig: Nutzer melden sich neu an, MCP-Clients verbinden sich neu. Das Postgres-Passwort wirkt nur bei der Initialisierung des leeren Volumes, danach zusätzlich per `ALTER USER nfv PASSWORD '...'` ändern.

## Konfiguration

- Variablen siehe Abschnitt „Swarm stack“ in `.env.example`. `docker stack deploy` liest keine `.env`, daher vorher in die Shell laden: `set -a; . ./.env; set +a`. Die lokalen Secrets aus der `.env` nutzt der Stack nicht.
- `DOMAIN` (Pflicht): Domain der App, z. B. `app.example.org`. Der Stack setzt `NFV_BACKEND_URL` und `NFV_FRONTEND_URL` beide auf `https://${DOMAIN}`; daraus leitet das Backend Issuer, Redirect-URIs, CORS und PayPal-Return-URLs ab. Das Backend ruft die URL selbst auf (Issuer-Discovery), sie muss also auch aus dem Container erreichbar sein (Hairpin über die öffentliche IP).
- `VITE_OIDC_AUTHORITY` ist Build-Zeit. Die Stack-Images bauen mit `same-origin`: Das Frontend nimmt zur Laufzeit den eigenen Origin als IdP (`frontend/src/auth/oidcConfig.ts`), das Image hängt also nicht von der Domain ab. Lokal bleibt `http://localhost:8080`.
- MCP-Clients (Claude, ChatGPT) erreichen den Server unter `https://${DOMAIN}/mcp`. Das Backend braucht ausgehendes HTTPS (Port 443), um die Client ID Metadata Documents der Clients abzurufen, siehe [authentication.md](authentication.md#mcp-clients-cimd).
- PayPal: `PAYPAL_ENVIRONMENT` (`sandbox`/`live`). In der PayPal-App muss die Return URL `https://${DOMAIN}/paypal/connected` eingetragen sein.

## TLS (Caddy)

- **Service `caddy`:** eigenes Image aus `deploy/caddy` (`caddy:2.10-alpine` mit `Caddyfile`), von CI gebaut. Das Caddyfile steckt im Image, weil `docker stack deploy` aus der Pipeline keine Konfigurationsdateien des Runners mitnehmen kann (`configs.content` kann `docker stack` nicht).
- **Zertifikat:** Caddy holt und erneuert das Let's-Encrypt-Zertifikat für `DOMAIN` selbst und leitet http auf https um. Voraussetzungen: DNS-Eintrag (A/AAAA) von `DOMAIN` auf den Server, Ports 80 und 443 (TCP, für HTTP/3 auch 443/UDP) aus dem Internet erreichbar und auf dem Server frei. Zertifikate und ACME-Konto liegen im Volume `caddy-data`, nicht löschen (Rate Limits von Let's Encrypt).
- **Ports:** nur Caddy, im Host-Modus (echte Client-IP, Placement `node.role == manager`, Update `stop-first`). Backend und Frontend sind von außen nicht mehr direkt erreichbar.
- **Routing (eine Origin `https://${DOMAIN}`):** ans Backend gehen `/api/*`, `/mcp`, `/mcp/*`, `/oauth2/*`, `/.well-known/*`, `/userinfo`, `/connect/*`, `/login`, `/logout`, `/register`, `/account/*`, `/error`, `/swagger-ui*`, `/v3/api-docs*`; alles andere ans Frontend. Neue Backend-Pfade außerhalb von `/api` im `Caddyfile` ergänzen, Frontend-Routen dürfen diese Pfade nicht nutzen.
- **Forwarded-Header:** `SERVER_FORWARD_HEADERS_STRATEGY=native`, Tomcat übernimmt `X-Forwarded-Proto`/`-For` nur von privaten Adressen (Caddy im Overlay-Netz), Redirects (z. B. auf `/login`) bleiben https.
- Deploy: automatisch nach jedem Merge auf `main`, siehe [Continuous Deployment](#continuous-deployment). Von Hand (Admin-Nutzer, nicht `deploy`): `docker stack deploy --with-registry-auth -c docker-compose.yml nfv`, Status `docker stack ps nfv`, Logs `docker service logs -f nfv_backend`.

## Continuous Deployment

Übernommen aus Polymoose Grammar, aber nur eine Umgebung (GitHub-Environment `Test`), kein Test/Prod-Split.

### Ablauf

- **Workflow „Build and deploy“** (`.github/workflows/build.yml`), bei jedem Push auf `main` (also jedem Merge) und von Hand:
  1. Backend- und Frontend-Tests (ruft `backend.yml` und `frontend.yml` auf; diese laufen sonst nur für andere Branches und Pull Requests).
  2. Images bauen und nach GHCR pushen, Tags `main` und `sha-<commit>`.
  3. Prüfen, dass alle Images (backend, frontend, caddy) mit beiden Tags in der Registry liegen.
  4. Workflow „Deploy“ aufrufen (`secrets: inherit`).
- **Workflow „Deploy“** (`.github/workflows/deploy.yml`), nur auf `main`, Environment `Test`, nie parallel (Concurrency-Gruppe `nfv-deploy`):
  - prüft, dass die Images `sha-<commit>` existieren,
  - setzt `BACKEND_IMAGE`/`FRONTEND_IMAGE` auf diese Tags (ein Deploy ist reproduzierbar, nicht „latest main“),
  - rendert `docker-compose.yml` mit `docker stack config` auf dem Runner (Konfigurationsfehler brechen vor dem Server ab),
  - schickt das Ergebnis per SSH auf stdin an `deploy-stack nfv` auf dem Server.
- **Aufruf statt `workflow_run`:** So bleibt `github.ref` = `main` und der Commit des Builds; Branch-Regeln des Environments greifen.
- **Rollback:** „Deploy“ von Hand auf einem älteren Commit von `main` starten (Actions → Deploy → Run workflow, Commit über den Branch-Stand). Swarm rollt Backend/Frontend zusätzlich selbst zurück, wenn ein Update fehlschlägt (`failure_action: rollback`).

### Deployment-User auf dem Server

- `deploy/setup-deploy-user.sh` (aus Polymoose übernommen) richtet auf dem Swarm-Manager (Ubuntu, Docker und OpenSSH installiert, `docker swarm init` erledigt) den Nutzer `deploy` ein:
  - Mitglied der Gruppe `docker`, Passwort gesperrt, nur Public-Key-Login.
  - `authorized_keys` mit genau einem Key und `restrict,command="/usr/local/bin/deploy-stack"`, zusätzlich `ForceCommand` per `/etc/ssh/sshd_config.d/00-deploy-user.conf`: keine Shell, kein TTY, kein Forwarding.
  - `/usr/local/bin/deploy-stack` akzeptiert nur `deploy-stack STACK_NAME` und führt `docker stack deploy --with-registry-auth -c - STACK_NAME` mit dem Stack von stdin aus.
  - Optional `docker login ghcr.io` für den Nutzer `deploy` (Token nur aus Datei, nicht als Argument).
  - Docker-Zugriff ist praktisch Root. Keine sudo-Rechte; Secrets, Verzeichnisse usw. legt ein Admin an.
- **Einrichtung:**
  1. Key-Paar nur für CI erzeugen (lokal): `ssh-keygen -t ed25519 -N '' -C nfv-deploy -f nfv-deploy`.
  2. GHCR-Token (die Images sind privat): Personal Access Token (classic) mit nur `read:packages`, von einem Konto mit Lesezugriff auf `kehrbros/vacantagent`, in eine Datei schreiben, z. B. `ghcr-token`.
  3. `nfv-deploy.pub`, `ghcr-token` und `deploy/setup-deploy-user.sh` auf den Server kopieren und dort ausführen: `sudo bash setup-deploy-user.sh nfv-deploy.pub <github-user> ghcr-token`, danach `ghcr-token` löschen. Ohne GHCR-Teil: `sudo bash setup-deploy-user.sh nfv-deploy.pub`, Login später mit `sudo -iu deploy docker login ghcr.io -u <github-user>`.
  4. Gibt es `AllowUsers`/`AllowGroups` in der SSH-Konfiguration, `deploy` dort ergänzen.
  5. Datenverzeichnis anlegen und Extrakt kopieren: `sudo mkdir -p /home/deploy/data/osm /home/deploy/data/graphs`, Extrakt nach `/home/deploy/data/osm/`.
  6. DNS-Eintrag von `DOMAIN` auf den Server, Firewall für 80/TCP, 443/TCP und 443/UDP öffnen. Kein anderer Dienst darf 80/443 belegen (auch kein alter Stack mit veröffentlichten Ports).
  7. Docker Secrets `nfv_*` anlegen (Admin, siehe [Secrets](#secrets)).
  8. Host-Key für GitHub: `ssh-keyscan -p <port> <host>` und mit dem Fingerprint auf dem Server vergleichen (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`). Bei Port ≠ 22 steht der Host als `[host]:port` in der Zeile.
- Erneut ausführen ersetzt den Key (Backup unter `/root/deploy-setup-backup.*`).
- Ein vorher von Hand unter einem anderen Nutzer deployter Stack `nfv` wird einfach übernommen. Hieß der Stack anders, den alten mit `docker stack rm` entfernen (Volumes heißen `<stack>_postgres-data`, Daten bei Namenswechsel vorher sichern).

### GitHub-Konfiguration

- **Environment `Test`** (Settings → Environments), empfohlen mit Deployment-Branch-Regel „nur `main`“.
- **Secrets im Environment `Test`:**

  | Secret | Inhalt |
  |---|---|
  | `DEPLOY_HOST` | Hostname oder IP des Swarm-Managers |
  | `DEPLOY_PORT` | SSH-Port, z. B. `22` |
  | `DEPLOY_USER` | `deploy` |
  | `DEPLOY_SSH_KEY` | Private Key `nfv-deploy` (komplette Datei inkl. `-----BEGIN/END …-----`) |
  | `DEPLOY_KNOWN_HOSTS` | Ausgabe von `ssh-keyscan -p <port> <host>` (geprüft, s. o.) |

- **Variablen:**

  | Variable | Ebene | Inhalt |
  |---|---|---|
  | `DOMAIN` | `Test` | Domain der App ohne Schema, z. B. `app.example.org`, Pflicht |
  | `NFV_HOST_DATA_DIR` | `Test` | z. B. `/home/deploy/data`, Pflicht |
  | `PAYPAL_ENVIRONMENT` | `Test` | `sandbox` (Standard) oder `live` |

- Die App-Secrets (PayPal, OpenAI, LiteAPI, Signaturschlüssel, Postgres-Passwort) liegen nicht in GitHub, sondern als Docker Secrets `nfv_*` auf dem Server.
- Das Image-Push nutzt `GITHUB_TOKEN` (`packages: write`), dafür muss das Repository Schreibzugriff auf die Packages `vacantagent/backend` und `vacantagent/frontend` haben (Package settings → Manage Actions access), ab dem ersten Lauf auch für `vacantagent/caddy`.

## Offen

- Backups des Postgres-Volumes.
- Healthchecks über Spring Boot Actuator.
