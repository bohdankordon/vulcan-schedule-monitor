# Container deployment foundation

This is a single-host application + PostgreSQL foundation. HTTPS reverse proxy/TLS and database backup/restore tooling are deferred. `/connect` must **not** be treated as production-public until an HTTPS reverse proxy and matching `PUBLIC_BASE_URL` are configured in the later deployment phase.

## Build and start

Use Docker Engine/Desktop with Linux containers, BuildKit and Docker Compose v2 or newer. The locally validated and CI platform is Linux amd64. Build from the repository root; no local JAR or JDK is required:

```shell
docker build -t vulcan-schedule-monitor:production .
```

Copy `.env.production.example` to the ignored `.env.production` (`cp` on Linux/macOS, `Copy-Item` on PowerShell). Supply distinct random `POSTGRES_ADMIN_PASSWORD` and `POSTGRES_APP_PASSWORD` values, plus `VULCAN_MASTER_KEY` as the Base64 encoding of exactly 32 cryptographically random bytes. All three deliberately start empty and Compose rejects missing/empty values. Keep the master key stable with the database; replacing it makes existing encrypted sessions and remembered credentials unreadable. Store the environment file securely outside Git and restrict its filesystem access to the operator. Never paste it into logs or a PR.

```shell
docker compose --env-file .env.production -f compose.production.yml config --quiet
docker compose --env-file .env.production -f compose.production.yml up -d --wait
```

`config --quiet` validates without displaying interpolated secrets. Plain `config` prints them. Environment variables set in the invoking shell take precedence over the file, so check that provider switches in the shell are also disabled for the initial start. Values containing `$` should be single-quoted in the environment file to prevent interpolation. There are no secret build arguments, build environment files or secret image labels. `.dockerignore` allows only the wrapper, POM, main sources/resources and the optional offline browser test source.

The initial stack disables VULCAN connection, VULCAN monitoring and Telegram. A normal startup therefore makes no provider requests. Enable features deliberately with `VULCAN_CONNECTION_ENABLED`, `VULCAN_MONITORING_ENABLED` and `TELEGRAM_ENABLED`; Telegram additionally requires `TELEGRAM_BOT_TOKEN`. Secure connection requires the stable master key and a valid public HTTPS base URL. Monitoring requires secure connection. Existing configuration validation remains in force. This PR supplies no public deployment instructions or provider smoke commands.

`APP_PORT` defaults to 8080; Compose publishes only `127.0.0.1:${APP_PORT}:8080`. PostgreSQL 18.6 has no host port and is reachable by the app at `postgres:5432`. The `postgres` role handles cluster bootstrap/administration; Spring, Flyway and JPA connect only as the non-superuser `schedule_monitor` role to database `schedule_monitor`. The default private Compose bridge permits outgoing provider access when explicitly enabled later. It does not isolate egress. The Compose smoke uses this same topology with a separate synthetic database and verifies all provider switches remain disabled in the running app.

## PostgreSQL roles and initialization

`POSTGRES_ADMIN_PASSWORD` is bootstrap/admin only: Compose supplies it as the official PostgreSQL image's `POSTGRES_PASSWORD`, with `POSTGRES_USER=postgres`. It is never passed to the application container. `POSTGRES_APP_PASSWORD` authenticates Spring/Flyway/JPA as `schedule_monitor`; PostgreSQL also receives it to initialize that role. Neither password is baked into an image.

The read-only `docker/postgres/init` bind mount supplies `10-create-application-role.sh` through the official `/docker-entrypoint-initdb.d` mechanism. On a **new empty data directory**, it creates/updates `schedule_monitor` with `LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`. That role owns database `schedule_monitor` and its `public` schema, allowing Flyway to create/manage application objects without cluster-administration rights. Public database/schema grants are revoked; no unrelated privileges or role memberships are granted.

The Bash script fails on a missing app password or SQL error. It uses psql's environment-variable import and quoted SQL literal interpolation, not shell-expanded SQL or password arguments. Initialization is transactional and statement/error-statement logging is disabled for that session; passwords are not printed. This script does **not** run again on normal database/container restarts and does not automatically repair existing roles.

Changing the tracked example, the real environment file, or the init script does not mutate an existing data volume or rotate its stored passwords. A volume created by the earlier single-role configuration may still have a superuser `schedule_monitor` and must not be reused with this configuration until an administrator explicitly reconciles its roles, ownership and credentials. This PR supplies no automatic existing-volume migration and never deletes existing data to apply the fix. The smoke test uses only its own fresh volume, then restarts PostgreSQL with that same volume to verify the non-superuser role persists.

## Image and browser contract

The multi-stage Dockerfile uses digest-pinned Eclipse Temurin `21.0.12_8-jdk-noble` for the Maven Wrapper build and `21.0.12_8-jre-noble` for the final Java 21 process. Dependencies are cached separately. The build skips tests; Maven verification runs separately. Spring Boot's supported [tools jarmode](https://docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html) extracts the four layers, and the final image starts the extracted `application.jar` directly.

Playwright **1.62.0** comes from `pom.xml`, the single version source. During image construction, the [Playwright Java CLI](https://playwright.dev/java/docs/browsers#install-system-dependencies) from the resolved application libraries runs `install --with-deps chromium`. This installs matching Chromium, Chromium headless shell and FFmpeg plus their supported Ubuntu dependencies. It installs no Firefox/WebKit and needs no hand-maintained OS dependency list. Browser files live under `/opt/playwright`; `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` prevents implicit runtime installation. Application startup needs no download.

`scripts/container/verify.py` parses the POM with an XML parser, compares its version with the final image's CLI and library, and launches the installed browser. Future Playwright upgrades change the POM and rebuild/retest the image together; there is no independent container browser version to drift. Update the version here as part of that upgrade. Refresh both pinned Java base tags/digests deliberately for security updates.

The application runs as `app`, UID/GID `10001:10001`. Application and browser files are root-owned and not writable by that user. Compose makes the root filesystem read-only, with a 512 MiB ephemeral `/tmp` and 256 MiB `/dev/shm`. `/tmp` explicitly allows execution because Playwright Java extracts and runs its bundled Node driver there; `nosuid,nodev` remain set. `HOME=/tmp` keeps browser profiles temporary. There is no app volume. All durable application state belongs in PostgreSQL.

Compose drops all application capabilities and sets `no-new-privileges`. It uses no privileged mode, host networking or host IPC. The existing Java launch options are preserved: Playwright's [Chromium sandbox option defaults to false](https://playwright.dev/java/docs/api/class-browsertype#browser-type-launch-option-chromium-sandbox). This PR adds no sandbox-disabling flags and does not claim a Chromium sandbox is active. Non-root execution and container isolation remain the boundary; do not use this image as a general browser for arbitrary untrusted websites. The offline launch is tested with the same user, read-only filesystem, capabilities and shared-memory limits as Compose.

## Health and shutdown

PostgreSQL's `pg_isready` healthcheck gates the app's **initial** startup through `service_healthy`. Flyway/JPA must initialize successfully before the application becomes ready. There are no fixed startup sleeps. Dependency ordering does not supervise later outages; application readiness is authoritative after startup.

The image's exec-form curl healthcheck calls `/actuator/health/liveness` every 30 seconds, with a 5-second timeout, 3 retries and a 60-second startup period. Readiness includes PostgreSQL; liveness excludes external dependencies. Probe responses disclose no database/account details. See [operational health](operations.md).

```shell
curl --fail http://127.0.0.1:8080/actuator/health
curl --fail http://127.0.0.1:8080/actuator/health/liveness
curl --fail http://127.0.0.1:8080/actuator/health/readiness
docker compose --env-file .env.production -f compose.production.yml ps
```

Use the selected `APP_PORT` if different. A temporary DB outage returns readiness 503 while liveness remains 200. Both services use `restart: unless-stopped` for process exits/daemon restarts; Docker health status alone does not restart containers. No readiness/provider-based restart loop is added.

`init: true` reaps browser children and forwards signals. Java uses an exec-form entrypoint and `STOPSIGNAL SIGTERM`. The application's `stop_grace_period: 120s` accommodates the existing 30-second Spring lifecycle phase budget and possible in-flight Telegram long polling. This is a maximum wait, not an intentional delay or a guarantee against every stalled network operation. PostgreSQL has a 60-second stop budget. No custom shutdown hooks are introduced.

```shell
docker compose --env-file .env.production -f compose.production.yml stop app
docker compose --env-file .env.production -f compose.production.yml logs --tail=100 -f app
```

Logs stay on stdout/stderr. Each service uses portable `json-file` rotation (10 MiB, 3 files); no log-file mounts or verbose request/browser/database logging are enabled. `JAVA_TOOL_OPTIONS` is optional and empty by default; use JVM container-awareness defaults. Do not put secrets into JVM options: the JVM may echo those options at startup.

## Persistence and stopping the stack

The named `postgres_data` volume mounts `/var/lib/postgresql`, the [PostgreSQL 18 image's persistence location](https://hub.docker.com/_/postgres#pgdata). Compose prefixes the volume with the project name. Keep a consistent project name/directory for a persistent deployment. Database initialization environment variables only apply to a new empty volume; changing the password in the file does not rotate an existing database password.

```shell
docker compose --env-file .env.production -f compose.production.yml down
```

`down` removes containers and the network and **retains database state**. `down -v` **destroys database state** by deleting named volumes. Do not use `-v` for routine operations. Backup/restore and backup retention are deferred to PR #16; this foundation is not a substitute for them.

## Reproducible validation

Run normal project verification plus the container harness (Python 3 standard library and Docker only):

```shell
./mvnw spotless:apply
./mvnw -B -ntp verify
python3 scripts/container/verify.py
git diff --check
```

On Windows use `.\mvnw.cmd` and `python`. The harness builds the production image itself; `--skip-build` reuses an already built production tag for local iteration. It validates Compose with synthetic secrets, rejects missing secrets, checks Java 21/UID/artifact/healthcheck/version consistency, and scans image history/config/environment and every exported filesystem layer for all three synthetic secret markers. Runtime-injected environment values naturally exist in running containers and are not image-layer leaks.

The optional `browser-smoke` Docker target adds a compiled test helper to the exact runtime stage, launches Chromium at a data URL, and clicks a button under `--network none`. The production target contains no helper or debug endpoint. The Compose smoke uses a unique disposable project/volume/network, checks all three HTTP probes, and authenticates as `schedule_monitor` to verify all five elevated role flags are false, database/schema/table ownership, Flyway's recorded migration user, absence of role memberships, and the running application's database sessions. Both `CREATE DATABASE` and `CREATE ROLE` must fail with SQLSTATE `42501`. A synthetic password containing SQL metacharacters exercises literal quoting without exposing credentials.

The harness stops only its own PostgreSQL, verifies readiness 503/liveness 200 with no app restart, checks recovery on the same named volume, repeats the role checks, and verifies initialization ran only once with no secrets in startup/database logs. It also verifies Spring shutdown under SIGTERM. Provider switches are disabled and inherited operator configuration is filtered; provider request counts follow from disabled runtime switches and an empty synthetic database, not packet capture. Finally it removes **only its disposable test volume**. Developer PostgreSQL data is never selected. The same harness runs in the `Container build and smoke` PR CI job; Maven tests and Dependency Review remain separate.
