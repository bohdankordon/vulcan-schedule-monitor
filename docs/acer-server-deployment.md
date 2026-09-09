# Acer-Server Standalone Production Deployment Runbook

This document details the authoritative deployment runbook for hosting Vulcan Schedule Monitor on `acer-server`.

---

## 1. Host Architecture & Production Invariants

`acer-server` is a dedicated, production-configured Linux server running Ubuntu 26.04.1 LTS:
- **Hardware**: AMD Ryzen 5 3500U (4 cores / 8 threads), 5.2 GiB RAM, 4 GiB swap.
- **Storage**: 100 GiB root Logical Volume (`ubuntu-lv`, ~86 GiB free) on NVMe SSD, ext4 filesystem.
- **Network & Access**: LAN IP `192.168.1.27`, public IP `83.24.101.64` (observed during provisioning / validation time; the durable invariant is that DDNS hostname `vulcan-schedule-monitor.dns-dns.com` resolves to the current public IPv4).
- **Firewall & Ingress**: UFW active (ports 22/tcp, 80/tcp, and 443/tcp allowed). Router forwards WAN TCP 80/443 to `192.168.1.27`.
- **Docker**: Docker Engine 29.8.0, Compose v5.5.1, Buildx v0.37.0.

### Self-Contained Deployment Topology
Vulcan Schedule Monitor deploys as a fully self-contained stack using the authoritative `compose.production.yml` and canonical project identity `vulcan-schedule-monitor-prod`:

```
           Internet
              |
              v
        Router TCP 80/443
              |
              v
     Vulcan Bundled Caddy
        (host 80/443 -> container 8080/8443)
              |
              v
         edge network (isolated bridge)
              |
              v
         Spring Boot App (UID 10001)
              |
              v
       backend network (internal bridge)
              |
              v
     PostgreSQL 18.6 (non-superuser role)
```

**Security & Resource Invariants**:
- **Host Port Restriction**: Bundled Caddy is the **only** service publishing host ports (80/tcp, 443/tcp, 443/udp). Spring Application and PostgreSQL publish **zero** host ports.
- **Network Segmentation**: Caddy connects only to `vulcan-schedule-monitor-prod_edge`; PostgreSQL connects only to `vulcan-schedule-monitor-prod_backend` (`internal: true`).
- **Canonical Project Identity**: `COMPOSE_PROJECT_NAME=vulcan-schedule-monitor-prod` is used consistently across all build, startup, log, backup, restore, and systemd units.
- **Resource Naming**:
  - Containers: `vulcan-schedule-monitor-prod-caddy-1`, `vulcan-schedule-monitor-prod-app-1`, `vulcan-schedule-monitor-prod-postgres-1`
  - Networks: `vulcan-schedule-monitor-prod_edge`, `vulcan-schedule-monitor-prod_backend`
  - Volumes: `vulcan-schedule-monitor-prod_postgres_data`, `vulcan-schedule-monitor-prod_caddy_data`, `vulcan-schedule-monitor-prod_caddy_config`
- **Enforced Container Memory Limits (`mem_limit`)**:
  - `app`: 2 GiB hard limit (`mem_limit: 2g`).
  - `postgres`: 1 GiB hard limit (`mem_limit: 1g`).
  - `caddy`: 512 MiB hard limit (`mem_limit: 512m`).
  - Combined container footprint ceiling is 3.5 GiB max, preserving at least 1.7 GiB of host RAM for Ubuntu kernel, systemd, sshd, and Wi-Fi hardware buffers on `acer-server`.
- **JVM Memory Allocation**: Inside the 2 GiB `app` container, Java heap is bounded via `-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError`, establishing a 1.0 GiB maximum heap and guaranteeing 1.0 GiB of non-heap headroom within the container for Playwright Chromium, Node driver, and native OS threads.

---

## 2. Temporary Edge State & Cutover Architecture

`acer-server` currently runs a temporary generic edge proxy (`/opt/acer-server-edge`, Compose file `compose.yaml`, container `acer-server-caddy`) which holds host ports 80 and 443 and serves a 503 placeholder with valid Let's Encrypt TLS for `vulcan-schedule-monitor.dns-dns.com`.

During real deployment, a staged cutover sequence is executed:
1. `postgres` and `app` start first internally, leaving temporary Caddy active.
2. Internal application health is verified over Docker networks.
3. Temporary Caddy is stopped via `/opt/acer-server-edge/compose.yaml`, releasing host ports 80/443.
4. Vulcan bundled Caddy is started, obtaining its own production TLS certificate.
5. If public cutover encounters issues, immediate rollback restarts the temporary edge.

---

## 3. Post-Merge Deployment Sequence

### Step 1: Create Host Directories
Create root-owned deployment and backup paths on `acer-server`:
```bash
sudo mkdir -p /opt/vulcan-schedule-monitor
sudo mkdir -p /srv/vulcan-schedule-monitor/backups
sudo chmod 755 /opt/vulcan-schedule-monitor
sudo chmod 700 /srv/vulcan-schedule-monitor/backups
```

### Step 2: Clone Repository & Check Out Exact Merged Commit
Clone the repository into the deployment directory and check out the verified SHA:
```bash
sudo git clone https://github.com/bohdankordon/vulcan-schedule-monitor.git /opt/vulcan-schedule-monitor
cd /opt/vulcan-schedule-monitor
sudo git checkout <MERGED_COMMIT_SHA>
```

### Step 3: Run Acer-Server Preflight Check
Run the read-only preflight script directly from the repository checkout:
```bash
cd /opt/vulcan-schedule-monitor
bash scripts/deployment/acer-server-preflight.sh
```
*(Optional workstation preflight before SSH: `Get-Content scripts/deployment/acer-server-preflight.sh -Raw | ssh acer-server "bash -s"`).*
Verify that all checks output `[PASS]` and overall preflight status is `PASSED`.

### Step 4: Create Production Environment File
Copy the template to `/opt/vulcan-schedule-monitor/.env.production` with strict permissions:
```bash
sudo cp deploy/acer-server/.env.production.example .env.production
sudo chown root:root .env.production
sudo chmod 600 .env.production
```

### Step 5: Generate Random Production Secrets
Generate strong random credentials and place them into `.env.production`:
```bash
# Generate unique passwords:
POSTGRES_ADMIN_PASSWORD=$(openssl rand -hex 24)
POSTGRES_APP_PASSWORD=$(openssl rand -hex 24)
VULCAN_MASTER_KEY=$(openssl rand -base64 32)

# Insert into .env.production safely without echoing:
sudo sed -i "s|^POSTGRES_ADMIN_PASSWORD=.*|POSTGRES_ADMIN_PASSWORD=${POSTGRES_ADMIN_PASSWORD}|" .env.production
sudo sed -i "s|^POSTGRES_APP_PASSWORD=.*|POSTGRES_APP_PASSWORD=${POSTGRES_APP_PASSWORD}|" .env.production
sudo sed -i "s|^VULCAN_MASTER_KEY=.*|VULCAN_MASTER_KEY=${VULCAN_MASTER_KEY}|" .env.production
```

### Step 6: Confirm Disabled Provider Switches
Confirm `.env.production` contains:
```ini
VULCAN_CONNECTION_ENABLED=false
VULCAN_MONITORING_ENABLED=false
TELEGRAM_ENABLED=false
TELEGRAM_BOT_TOKEN=
```

### Step 7: Confirm Canonical Project, Origin, and Resource Settings
Confirm settings in `.env.production`:
```ini
COMPOSE_PROJECT_NAME=vulcan-schedule-monitor-prod
EDGE_BIND_ADDRESS=0.0.0.0
EDGE_HTTP_PORT=80
EDGE_HTTPS_PORT=443
CADDY_SITE_ADDRESS=vulcan-schedule-monitor.dns-dns.com
PUBLIC_BASE_URL=https://vulcan-schedule-monitor.dns-dns.com
JAVA_TOOL_OPTIONS=-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError
```

### Step 8: Validate Compose Configuration
Validate configuration syntax:
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml config --quiet
```

### Step 9: Build Production Image Deterministically
Build the production application image directly on the host from source:
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml build app
```

### Step 10: Staged Startup — Postgres and App Only
Start backend services while leaving bundled Caddy stopped (omitting `--no-deps` honors `depends_on: postgres: condition: service_healthy` so Postgres reaches healthy status before the application initializes):
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml up -d postgres app
```

### Step 11: Verify Internal Application Readiness
Wait for Postgres and App to become healthy:
```bash
# Check container status:
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml ps

# Verify internal health via container curl:
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml exec app curl -s http://127.0.0.1:8080/actuator/health/readiness
```
Expect `{"status":"UP"}`.

### Step 12: Verify Database Role Separation
Confirm that application connects under restricted `schedule_monitor` role:
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml exec postgres psql -U schedule_monitor -d schedule_monitor -c "SELECT current_user, usesuper FROM pg_user WHERE usename = current_user;"
```
Expect `usesuper = f`.

### Step 13: Create Initial Database Baseline Backup
Run the database backup tool to establish a clean post-migration baseline:
```bash
sudo bash scripts/database/backup.sh --env-file .env.production --project-name vulcan-schedule-monitor-prod --output-dir /srv/vulcan-schedule-monitor/backups
```
Verify output prints `BACKUP SUCCESS` and created `.dump`, `.sha256`, and `.meta` files.

### Step 14: Verify Temporary Generic Edge
Confirm temporary edge is still responding:
```bash
curl -i https://vulcan-schedule-monitor.dns-dns.com/__edge_health
```
Expect HTTP `200 OK` with body `edge ok`.

### Step 15: Stop Temporary Generic Edge
Stop the temporary Caddy container (preserving its volumes and network):
```bash
cd /opt/acer-server-edge
sudo docker compose -f compose.yaml stop
cd /opt/vulcan-schedule-monitor
```

### Step 16: Verify Host Ports 80 and 443 Released
Confirm no process is listening on host ports 80/443:
```bash
sudo ss -lntup | grep -E ':(80|443) ' || echo "PORTS_80_443_RELEASED"
```

### Step 17: Start Vulcan Bundled Caddy
Launch the bundled Caddy reverse proxy:
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml up -d caddy
```

### Step 18: Verify Public HTTPS & Actuator Endpoints
Test public edge reachability and automated Let's Encrypt TLS:
```bash
# 1. HTTP to HTTPS redirect:
curl -i http://vulcan-schedule-monitor.dns-dns.com/actuator/health
# Expect HTTP 308 redirect to https://vulcan-schedule-monitor.dns-dns.com:443/...

# 2. Public HTTPS health:
curl -i https://vulcan-schedule-monitor.dns-dns.com/actuator/health
# Expect HTTP 200 OK with {"status":"UP"}

# 3. Liveness and Readiness probes:
curl -i https://vulcan-schedule-monitor.dns-dns.com/actuator/health/liveness
curl -i https://vulcan-schedule-monitor.dns-dns.com/actuator/health/readiness
```

### Step 19: Verify Host Port Invariants
Confirm only Caddy publishes ports on the host:
```bash
sudo ss -lntup | grep -E ':(80|443|8080|8443|5432) '
```
Only ports `80` and `443` (Caddy docker-proxy) should be listening. Neither `8080` nor `5432` must appear.

### Step 20: Verify Container Process Health
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml ps
```
All three containers (`caddy`, `app`, `postgres`) must report `Up` / `healthy`.

### Step 21: Verify Database Volume Persistence
Confirm named volume `vulcan-schedule-monitor-prod_postgres_data` is mounted and retained:
```bash
sudo docker volume inspect vulcan-schedule-monitor-prod_postgres_data
```

### Step 22: Install Automated Daily Backup Timer
Install and enable the systemd backup service and timer:
```bash
sudo cp deploy/acer-server/systemd/vulcan-schedule-monitor-backup.service /etc/systemd/system/
sudo cp deploy/acer-server/systemd/vulcan-schedule-monitor-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now vulcan-schedule-monitor-backup.timer
sudo systemctl list-timers | grep vulcan-schedule-monitor-backup
```

### Step 23: Keep Providers Disabled & Complete Initial Rollout
Ensure providers remain off (`VULCAN_CONNECTION_ENABLED=false`, `TELEGRAM_ENABLED=false`). The server is now running a hardened, production-ready stack in baseline standby.

### Step 24: Human Verification
Perform operator visual check:
- Public TLS certificate issuer and expiration dates.
- Container logs (`sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml logs --tail 50`).
- No errors, no secret leaks in logs.

### Step 25: Post-Rollout Controlled Reboot Acceptance
Once initial rollout is verified:
1. Execute controlled reboot: `sudo systemctl reboot`.
2. Wait for SSH reconnection.
3. Verify all containers automatically resume (`restart: unless-stopped`):
   ```bash
   sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml ps
   curl -i https://vulcan-schedule-monitor.dns-dns.com/actuator/health/readiness
   ```
4. Verify backup timer remains active: `systemctl status vulcan-schedule-monitor-backup.timer`.

### Step 26: Provider Enablement Phase (Post-Deployment)
When ready to connect to upstream services:
1. **Enable VULCAN integration**: set `VULCAN_CONNECTION_ENABLED=true` in `.env.production` and restart the app container (`sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml up -d --no-deps app`).
2. **Enable Telegram notifications**: set `TELEGRAM_ENABLED=true` and provide `TELEGRAM_BOT_TOKEN` in `.env.production`, then restart the app container.

> [!NOTE]
> `TELEGRAM_ENABLED` is the operator-facing `.env.production` input variable. Compose maps this variable to `TELEGRAM_BOT_ENABLED: ${TELEGRAM_ENABLED:-false}` in the container's environment for Spring Boot. Operators only manage `TELEGRAM_ENABLED` in `.env.production` and do not need to maintain `TELEGRAM_BOT_ENABLED` manually.

---

## 4. Rollback Strategies

### Level 1: Edge Rollback (Public Ingress Failure)
If Vulcan Caddy fails to obtain a certificate or cannot serve public traffic after cutover:
```bash
# 1. Stop Vulcan Caddy:
cd /opt/vulcan-schedule-monitor
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml stop caddy

# 2. Restart temporary generic edge:
cd /opt/acer-server-edge
sudo docker compose -f compose.yaml start

# 3. Verify placeholder HTTPS is restored:
curl -i https://vulcan-schedule-monitor.dns-dns.com/__edge_health
```
*(No data volumes or certificates are destroyed on either side).*

### Level 2: Application Rollback (Database / App Failure)
Application rollback depends on whether a verified baseline backup has already been established:

#### Case A: Failure Before a Baseline Backup Exists (Day-1 Initial Migration / Startup Failure)
If the initial Flyway migration or application initialization fails during day-1 rollout (Steps 10–12):
- **No baseline backup exists yet**: Do NOT attempt to run `restore.sh`, as there is no prior valid backup archive to restore from.
- **Keep external integrations disabled**: Maintain `VULCAN_CONNECTION_ENABLED=false`, `VULCAN_MONITORING_ENABLED=false`, and `TELEGRAM_ENABLED=false`.
- **Stop affected services for troubleshooting**:
  ```bash
  cd /opt/vulcan-schedule-monitor
  sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml stop app postgres
  ```
- **Inspect diagnostic logs**:
  ```bash
  sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml logs app postgres
  ```
- **Persistent Volume Retained**: Running `docker compose stop` or `docker compose down` removes or stops containers and networks, but retains persistent named volumes (`vulcan-schedule-monitor-prod_postgres_data`). It does *not* return the host to a pristine pre-deployment state.
- **Disposable Volume Reset (Deliberate Operator Action)**: If this is an initial failed deployment with no historical production data and a clean database reset is desired before retrying, removing the volume requires explicit operator intent:
  ```bash
  sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml down -v
  ```
  *(This is a manual volume purge for clean retry on fresh deploys, NOT a database restore).*

#### Case B: Failure After a Valid Baseline or Production Backup Exists (Post-Step 13 / Routine Upgrades)
If application startup, schema migration, or health checks fail during an upgrade or anytime after a verified baseline/routine backup exists in `/srv/vulcan-schedule-monitor/backups`:

- **Do NOT run `docker compose down`**: The restore script (`scripts/database/restore.sh`) enforces safety invariants by inspecting the existing `app` container to ensure provider integrations are disabled, and automatically handles stopping and restarting `app`. Running `docker compose down` destroys the `app` container and causes `restore.sh` to abort immediately with `Expected exactly one existing application container`.
- **Do NOT recreate PostgreSQL**: The database service must remain running and healthy to execute the restore.
- **Do NOT recreate Caddy**: Bundled Caddy configuration does not need adjustment for provider disablement.

##### 1. Provider Disablement & App Container Recreation
`restore.sh` does NOT merely inspect `.env.production`. It inspects the EXISTING application container's `Config.Env` directly. Editing `.env.production` alone is NOT sufficient after providers have previously been enabled, because running containers retain their initial environment.

Distinct environments must be aligned:
- **Operator Configuration (`.env.production`)**:
  ```ini
  VULCAN_CONNECTION_ENABLED=false
  VULCAN_MONITORING_ENABLED=false
  TELEGRAM_ENABLED=false
  ```
- **Effective App Container Environment (`Config.Env`)**:
  (Compose maps `TELEGRAM_ENABLED` -> `TELEGRAM_BOT_ENABLED: ${TELEGRAM_ENABLED:-false}` for Spring Boot):
  ```ini
  VULCAN_CONNECTION_ENABLED=false
  VULCAN_MONITORING_ENABLED=false
  TELEGRAM_BOT_ENABLED=false
  ```

Before restore, if the current `app` container was created with any provider enabled:
1. Edit `/opt/vulcan-schedule-monitor/.env.production` to set:
   ```ini
   VULCAN_CONNECTION_ENABLED=false
   VULCAN_MONITORING_ENABLED=false
   TELEGRAM_ENABLED=false
   ```
2. Recreate **ONLY** the application container using the canonical project:
   ```bash
   cd /opt/vulcan-schedule-monitor
   sudo docker compose \
     --project-name vulcan-schedule-monitor-prod \
     --env-file .env.production \
     -f compose.production.yml \
     up -d --no-deps --force-recreate app
   ```
   *(Note: The app container must continue to exist so `restore.sh` can inspect it. It is acceptable if `app` cannot reach readiness because the database is the component being recovered; the essential restore precondition is that the container exists and its effective provider switches are `false`).*

##### 2. Safely Verify Effective Container Flags
Verify that effective provider flags are disabled **without dumping secrets** (`POSTGRES_APP_PASSWORD`, `VULCAN_MASTER_KEY`, `TELEGRAM_BOT_TOKEN`, or the complete `Config.Env`). Use a fixed-verdict Docker inspect template equivalent to `restore.sh`'s safety guard:
```bash
sudo docker inspect --format '{{range $name := split "VULCAN_CONNECTION_ENABLED VULCAN_MONITORING_ENABLED TELEGRAM_BOT_ENABLED" " "}}{{range $.Config.Env}}{{if eq (index (split . "=") 0) $name}}{{printf "%s=" $name}}{{if eq . (printf "%s=false" $name)}}disabled{{else}}unsafe{{end}}{{"\n"}}{{end}}{{end}}{{end}}' vulcan-schedule-monitor-prod-app-1
```
Expected safe output (reveals ONLY the three provider flags):
```
VULCAN_CONNECTION_ENABLED=disabled
VULCAN_MONITORING_ENABLED=disabled
TELEGRAM_BOT_ENABLED=disabled
```
If any flag reports `unsafe` or is missing, do not proceed with restore until `.env.production` and the recreated `app` container are corrected.

##### 3. Ensure Database Service Is Running
Ensure `postgres` is running and healthy:
```bash
sudo docker compose --project-name vulcan-schedule-monitor-prod --env-file .env.production -f compose.production.yml up -d postgres
```

##### 4. Invoke Production Database Restore Directly
Only after verifying effective flags, execute `restore.sh` pointing to the trusted backup archive:
```bash
sudo bash scripts/database/restore.sh \
  --archive /srv/vulcan-schedule-monitor/backups/<BACKUP_FILE>.dump \
  --confirm schedule_monitor \
  --env-file .env.production \
  --project-name vulcan-schedule-monitor-prod \
  --output-dir /srv/vulcan-schedule-monitor/backups
```
`restore.sh` remains authoritative for:
1. Validating SHA256 checksum and metadata sidecars (`.sha256` and `.meta`).
2. Verifying database connection, role permissions, and archive structure.
3. Inspecting the existing `app` container for provider safety switches (`Config.Env`).
4. Creating an automated safety backup of the current database state in `/srv/vulcan-schedule-monitor/backups`.
5. Gracefully stopping the `app` container.
6. Disallowing new connections, terminating active `schedule_monitor` sessions, drop/recreate the public schema, and execute `pg_restore`.
7. Restarting the `app` container and wait for `/actuator/health/readiness` (HTTP 200).

- **Never execute destructive global resets**: Never execute `docker volume prune` or delete persistent volumes unless deliberate data destruction is explicitly intended.
