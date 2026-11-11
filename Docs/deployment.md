# Deployment (Docker Swarm and local)

## Local with Docker Compose

- **File:** `docker-compose.local.yml`, services `postgres`, `backend`, `frontend`. All images are built from the Dockerfiles in the repo, no access to the container registry and no local Java/Maven/Node needed. The backend builds `backend/Dockerfile.local` from the sources (multi-stage with Maven, context via `Dockerfile.local.dockerignore`), CI still uses `backend/Dockerfile` with the previously built jar.
- **Secrets:** locally in the `.env` in the repo root (in `.gitignore`, never commit). Copy the template `.env.example`: `POSTGRES_PASSWORD` is required, `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `NFV_IDP_SIGNING_KEY`, `OPENAI_API_KEY` and `LITEAPI_API_KEY` are optional. Docker Compose reads the `.env` automatically.
- **Start:** `docker compose -f docker-compose.local.yml up --build`. Frontend http://localhost:18173 (port of the Vite dev server so that redirect URIs and PayPal return URL match, so not in parallel with `npm run dev`), backend http://localhost:18180 (deliberately rarely used ports instead of 5173/8080 so that other projects can run in parallel; change via `LOCAL_FRONTEND_PORT`/`LOCAL_BACKEND_PORT` in `.env`, issuer and `VITE_OIDC_AUTHORITY` follow, then `up --build`; `mvn spring-boot:run` and `mvn verify` also use 18180), login `test`/`test`.
- **Database:** PostgreSQL 17 with PostGIS as in the stack, data in the volume `postgres-data`, port `LOCAL_POSTGRES_PORT` (default 5432) for DB tools. Reset: `docker compose -f docker-compose.local.yml down -v`. The password only applies when the volume is first created.
- **Road network:** bind mounts `~/data/osm` (read-only) and `~/data/graphs` to `/data` (`NFV_DATA_DIR=/data`), host path via `NFV_HOST_DATA_DIR`. Extract by hand to `~/data/osm/new-zealand-latest.osm.pbf`, see [network.md](network.md#configuration). Without an extract the backend starts, routing responds with 503. `NFV_NETWORK_IMPORT_ON_START=false` in the `.env` skips the import.
- Without Docker Compose: `mvn spring-boot:test-run` starts the app with a PostGIS container (Testcontainers, Docker needed, data volatile), see README.

## Docker Swarm

- **Stack:** `docker-compose.yml` in the repo root, services `postgres` (PostgreSQL 17 with PostGIS), `backend` (Spring Boot, port 8080), `frontend` (nginx with the Vite build, port 80) and `caddy` (TLS proxy, ports 80/443) in the overlay network `nfv`. Only Caddy publishes ports, see [TLS (Caddy)](#tls-caddy).
- **Images:** on every push to `main` CI builds and pushes `ghcr.io/kehrbros/nofrustrationvacation/backend`, `…/frontend` and `…/caddy` with the tags `main` and `sha-<commit>` for `linux/amd64` and `linux/arm64` (the server is ARM/aarch64; QEMU, the Node build of the frontend runs natively via `--platform=$BUILDPLATFORM`), see [Continuous Deployment](#continuous-deployment). Other tags via `BACKEND_IMAGE`/`FRONTEND_IMAGE`/`CADDY_IMAGE`. Build locally: `cd backend && mvn -DskipTests verify`, then `docker compose build` in the root. `docker stack deploy` ignores `build` and needs the images on every node (registry or local with a single-node swarm).
- **Database:** PostgreSQL with PostGIS, in the stack `imresamu/postgis:17-3.5-alpine` (multi-arch build of `postgis/postgis`, which only exists for amd64; locally and in tests still `postgis/postgis:17-3.5-alpine`), host/DB/user via `POSTGRES_HOST`, `POSTGRES_DB`, `POSTGRES_USER`.
- **Postgres service:** data in the volume `postgres-data`. The volume is local to one node, hence placement `node.role == manager`. With several managers switch to a node label (`docker node update --label-add nfv.db=true <node>`, constraint `node.labels.nfv.db == true`).
- **Backend:** 1 replica, schedulers (exchange rates, PayPal status) and the matching of incidents run in the process. Signing key (secret) and authorisations of the IdP (database) survive a restart, logins and MCP connections stay valid. Swarm has no `depends_on`, the backend restarts until Postgres is reachable.
- **Road network:** bind mounts `${NFV_HOST_DATA_DIR}/osm` (read-only) and `…/graphs` to `/data` (`NFV_DATA_DIR=/data`). Extract not in the image and not in the repository, until the Geofabrik download by hand to `${NFV_HOST_DATA_DIR}/osm/new-zealand-latest.osm.pbf`, see [network.md](network.md#configuration).
  - `NFV_HOST_DATA_DIR` is required in the stack (absolute path on the server, e.g. `/home/deploy/data`). `docker stack config/deploy` cannot resolve nested defaults like `${A:-${HOME}/data}`, and the pipeline renders the file on the CI runner.
  - Create the directories beforehand (`mkdir -p /home/deploy/data/osm /home/deploy/data/graphs`), otherwise Swarm does not start the container.
  - Bind mounts are local to one node, hence backend with placement `node.role == manager` (like Postgres).
  - Memory limit backend 4G (NZ import several GB, graph memory-mapped outside the heap, `-XX:MaxRAMPercentage=75`). The first start with import takes a few minutes, routing responds with 503 until then.
- **Frontend:** 2 replicas, rolling update `start-first`. nginx forwards `/api/**` to `http://backend:8080` (`BACKEND_UPSTREAM`, template `frontend/nginx.conf`), in the stack Caddy routes `/api/**` directly to the backend. Login (OIDC) goes to `https://${DOMAIN}` (same-origin, see [TLS (Caddy)](#tls-caddy)).

## Secrets

- Docker secrets instead of environment variables, never in the repo or in `.env`:

  | Docker secret | File in the container | Property |
  |---|---|---|
  | `nfv_paypal_client_id` | `/run/secrets/PAYPAL_CLIENT_ID` | `nfv.paypal.client-id` |
  | `nfv_paypal_client_secret` | `/run/secrets/PAYPAL_CLIENT_SECRET` | `nfv.paypal.client-secret` |
  | `nfv_idp_signing_key` | `/run/secrets/NFV_IDP_SIGNING_KEY` | `nfv.auth.signing-key` |
  | `nfv_openai_api_key` | `/run/secrets/OPENAI_API_KEY` | `nfv.assistant.api-key` (extracting booking confirmations, cover photos; empty: manual input only, placeholder image) |
  | `nfv_liteapi_api_key` | `/run/secrets/LITEAPI_API_KEY` | `nfv.bookings.alternatives.api-key` (searching and booking alternative accommodations; empty: feature off) |
  | `nfv_postgres_password` | `/run/secrets/POSTGRES_PASSWORD` (backend), `/run/secrets/postgres_password` (Postgres, `POSTGRES_PASSWORD_FILE`) | `spring.datasource.password` |

- **How the backend reads them:** `spring.config.import=optional:configtree:/run/secrets/` in `application.properties`. Every file becomes a property with the file name as key (trailing newline is removed), the existing placeholders `${PAYPAL_CLIENT_ID:}` etc. resolve to it. Without `/run/secrets` (local) environment variables or `application-local.properties` apply as before, see [paypal.md](paypal.md).
- **Creating via script (recommended):** `bash deploy/create-secrets.sh <admin>@<host> [<port>]` locally in the repo root (Git Bash, Linux; Bash ≥ 4), with an admin user who may run Docker on the server (not `deploy`).
  - Asks for every secret hidden. Empty: unchanged. Value: new secret `nfv_<name>_v1` or next version. `generate` creates the signing key or Postgres password (openssl), `-` creates an empty secret (OpenAI/LiteAPI off).
  - Values only go via stdin over SSH, not as arguments.
  - Enters the current version of every secret under `name:` in `docker-compose.yml` and prints it. Then commit and push `docker-compose.yml`, the deploy then uses the new secrets.
- **Creating by hand** (before the first deploy, all six are required, adjust the names in `docker-compose.yml`):

  ```sh
  printf '%s' '<client-id>'     | docker secret create nfv_paypal_client_id -
  printf '%s' '<client-secret>' | docker secret create nfv_paypal_client_secret -
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 | docker secret create nfv_idp_signing_key -
  openssl rand -base64 24 | tr -d '\n' | docker secret create nfv_postgres_password -
  printf '%s' '<openai-api-key>' | docker secret create nfv_openai_api_key -   # create empty if no key
  printf '%s' '<liteapi-api-key>' | docker secret create nfv_liteapi_api_key -   # create empty if no key
  ```

- **Rotating:** secrets are immutable. Create a new secret with a new name, adjust `name:` in `docker-compose.yml`, deploy again. A new signing key invalidates all issued tokens: users log in again, MCP clients reconnect. The Postgres password only takes effect when the empty volume is initialised, afterwards change it additionally via `ALTER USER nfv PASSWORD '...'`.

## Configuration

- For variables see the section "Swarm stack" in `.env.example`. `docker stack deploy` does not read a `.env`, so load it into the shell first: `set -a; . ./.env; set +a`. The stack does not use the local secrets from the `.env`.
- `DOMAIN` (required): domain of the app, e.g. `app.example.org`. The stack sets `NFV_BACKEND_URL` and `NFV_FRONTEND_URL` both to `https://${DOMAIN}`; from this the backend derives issuer, redirect URIs, CORS and PayPal return URLs. The backend calls the URL itself (issuer discovery), for this Caddy has the alias `DOMAIN` in the overlay network: Docker DNS resolves the domain in the container directly to Caddy, without a detour via the public IP and the host firewall (which e.g. only allows Tailscale IPs on 443).
- `VITE_OIDC_AUTHORITY` is build time. The stack images build with `same-origin`: at runtime the frontend takes its own origin as IdP (`frontend/src/auth/oidcConfig.ts`), so the image does not depend on the domain. Locally it stays `http://localhost:18180`.
- MCP clients (Claude, ChatGPT) reach the server at `https://${DOMAIN}/mcp`. The backend needs outgoing HTTPS (port 443) to fetch the client ID metadata documents of the clients, see [authentication.md](authentication.md#mcp-clients-cimd).
- PayPal: `PAYPAL_ENVIRONMENT` (`sandbox`/`live`). The return URL `https://${DOMAIN}/paypal/connected` must be entered in the PayPal app.
- `NFV_MANUAL_SOURCE_ENABLED` (optional, default `false`): test endpoints `/api/v1/test/traffic-events`, in the stack from the GitHub variable of the same name, never in production, see [traffic-feed.md](traffic-feed.md#test-source-manual).

## TLS (Caddy)

- **Service `caddy`:** own image from `deploy/caddy` (`caddy:2.10-alpine` with `Caddyfile`), built by CI. The Caddyfile is in the image because `docker stack deploy` from the pipeline cannot take configuration files of the runner along (`docker stack` does not support `configs.content`).
- **Certificate:** Caddy obtains and renews the Let's Encrypt certificate for `DOMAIN` itself and redirects http to https. Prerequisites: DNS record (A/AAAA) of `DOMAIN` pointing to the server, ports 80 and 443 (TCP, for HTTP/3 also 443/UDP) reachable from the internet and free on the server. Certificates and ACME account are in the volume `caddy-data`, do not delete it (Let's Encrypt rate limits).
- **Ports:** only Caddy, in host mode (real client IP, placement `node.role == manager`, update `stop-first`). Backend and frontend are no longer directly reachable from outside.
- **Routing (one origin `https://${DOMAIN}`):** to the backend go `/api/*`, `/mcp`, `/mcp/*`, `/oauth2/*`, `/.well-known/*`, `/userinfo`, `/connect/*`, `/login`, `/logout`, `/register`, `/account/*`, `/error`, `/swagger-ui*`, `/v3/api-docs*`; everything else to the frontend. Add new backend paths outside `/api` to the `Caddyfile`, frontend routes must not use these paths.
- **Forwarded headers:** `SERVER_FORWARD_HEADERS_STRATEGY=native`, Tomcat accepts `X-Forwarded-Proto`/`-For` only from private addresses (Caddy in the overlay network), redirects (e.g. to `/login`) stay https.
- Deploy: automatically after every merge to `main`, see [Continuous Deployment](#continuous-deployment). By hand (admin user, not `deploy`): `docker stack deploy --with-registry-auth -c docker-compose.yml nfv`, status `docker stack ps nfv`, logs `docker service logs -f nfv_backend`.

## Continuous Deployment

Taken over from Polymoose Grammar, but only one environment (GitHub environment `Test`), no test/prod split.

### Flow

- **Workflow "Build and deploy"** (`.github/workflows/build.yml`), on every push to `main` (i.e. every merge) and by hand:
  1. Backend and frontend tests (calls `backend.yml` and `frontend.yml`; otherwise these only run for other branches and pull requests).
  2. Build images and push them to GHCR, tags `main` and `sha-<commit>`.
  3. Check that all images (backend, frontend, caddy) are in the registry with both tags.
  4. Call workflow "Deploy" (`secrets: inherit`).
- **Workflow "Deploy"** (`.github/workflows/deploy.yml`), only on `main`, environment `Test`, never in parallel (concurrency group `nfv-deploy`):
  - checks that the images `sha-<commit>` exist,
  - sets `BACKEND_IMAGE`/`FRONTEND_IMAGE` to these tags (a deploy is reproducible, not "latest main"),
  - renders `docker-compose.yml` with `docker stack config` on the runner (configuration errors abort before the server),
  - sends the result via SSH on stdin to `deploy-stack nfv` on the server.
- **Call instead of `workflow_run`:** this way `github.ref` = `main` and the commit of the build are kept; branch rules of the environment apply.
- **Rollback:** start "Deploy" by hand on an older commit of `main` (Actions → Deploy → Run workflow, commit via the branch state). Swarm additionally rolls back backend/frontend itself if an update fails (`failure_action: rollback`).

### Deployment user on the server

- `deploy/setup-deploy-user.sh` (taken over from Polymoose) sets up the user `deploy` on the swarm manager (Ubuntu, Docker and OpenSSH installed, `docker swarm init` done):
  - Member of the group `docker`, password locked, public key login only.
  - `authorized_keys` with exactly one key and `restrict,command="/usr/local/bin/deploy-stack"`, additionally `ForceCommand` via `/etc/ssh/sshd_config.d/00-deploy-user.conf`: no shell, no TTY, no forwarding.
  - `/usr/local/bin/deploy-stack` only accepts `deploy-stack STACK_NAME` and runs `docker stack deploy --with-registry-auth -c - STACK_NAME` with the stack from stdin.
  - Optional `docker login ghcr.io` for the user `deploy` (token only from a file, not as an argument).
  - Docker access is practically root. No sudo rights; secrets, directories etc. are created by an admin.
- **Setup:**
  1. Create a key pair for CI only (locally): `ssh-keygen -t ed25519 -N '' -C nfv-deploy -f nfv-deploy`.
  2. GHCR token (the images are private): personal access token (classic) with only `read:packages`, from an account with read access to `kehrbros/nofrustrationvacation`, write it to a file, e.g. `ghcr-token`.
  3. Copy `nfv-deploy.pub`, `ghcr-token` and `deploy/setup-deploy-user.sh` to the server and run there: `sudo bash setup-deploy-user.sh nfv-deploy.pub <github-user> ghcr-token`, then delete `ghcr-token`. Without the GHCR part: `sudo bash setup-deploy-user.sh nfv-deploy.pub`, log in later with `sudo -iu deploy docker login ghcr.io -u <github-user>`.
  4. If there is `AllowUsers`/`AllowGroups` in the SSH configuration, add `deploy` there.
  5. Create the data directory and copy the extract: `sudo mkdir -p /home/deploy/data/osm /home/deploy/data/graphs`, extract to `/home/deploy/data/osm/`.
  6. DNS record of `DOMAIN` pointing to the server, open the firewall for 80/TCP, 443/TCP and 443/UDP. No other service may occupy 80/443 (not even an old stack with published ports).
  7. Create Docker secrets `nfv_*` (admin, see [Secrets](#secrets)).
  8. Host key for GitHub: `ssh-keyscan -p <port> <host>` and compare with the fingerprint on the server (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`). With port ≠ 22 the host appears as `[host]:port` in the line.
- Running it again replaces the key (backup under `/root/deploy-setup-backup.*`).
- A stack `nfv` previously deployed by hand under another user is simply taken over. If the stack had a different name, remove the old one with `docker stack rm` (volumes are called `<stack>_postgres-data`, back up the data before a name change).

### GitHub configuration

- **Environment `Test`** (Settings → Environments), recommended with deployment branch rule "only `main`".
- **Secrets in the environment `Test`:**

  | Secret | Content |
  |---|---|
  | `DEPLOY_HOST` | host name or IP of the swarm manager |
  | `DEPLOY_PORT` | SSH port, e.g. `22` |
  | `DEPLOY_USER` | `deploy` |
  | `DEPLOY_SSH_KEY` | private key `nfv-deploy` (complete file incl. `-----BEGIN/END …-----`) |
  | `DEPLOY_KNOWN_HOSTS` | optional, output of `ssh-keyscan -p <port> <host>` (checked, see above). Without the secret the pipeline does not check the host key (no protection against man-in-the-middle, warning in the run) |

- **Variables:**

  | Variable | Level | Content |
  |---|---|---|
  | `DOMAIN` | `Test` | domain of the app without scheme, e.g. `app.example.org`, required |
  | `NFV_HOST_DATA_DIR` | `Test` | e.g. `/home/deploy/data`, required |
  | `PAYPAL_ENVIRONMENT` | `Test` | `sandbox` (default) or `live` |

- The app secrets (PayPal, OpenAI, LiteAPI, signing key, Postgres password) are not in GitHub but stored as Docker secrets `nfv_*` on the server.
- The image push uses `GITHUB_TOKEN` (`packages: write`), for this the repository must have write access to the packages `nofrustrationvacation/backend` and `nofrustrationvacation/frontend` (Package settings → Manage Actions access), from the first run also for `nofrustrationvacation/caddy`.

## Open

- Backups of the Postgres volume.
- Health checks via Spring Boot Actuator.
