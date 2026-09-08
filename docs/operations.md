# Operational health

The anonymous probe surface is limited to these GET requests on the application port:

| Path | Meaning |
| --- | --- |
| `/actuator/health/liveness` | Spring Boot `livenessState`: whether the process can continue operating or recover internally. |
| `/actuator/health/readiness` | Spring Boot `readinessState` plus the built-in JDBC `db` contributor for PostgreSQL. |
| `/actuator/health` | Aggregate health for simple service monitoring. Use the dedicated liveness probe for restart decisions. |

Healthy probes return HTTP 200 with `status: UP`. A database health failure makes readiness return HTTP 503 with `status: DOWN`; refusing traffic produces `OUT_OF_SERVICE` / 503. A database failure alone leaves liveness UP. Telegram, VULCAN, Internet availability, and provider rate limits are excluded from both probe groups. PostgreSQL is fundamental to persistent monitoring and outbox safety; provider failures retain their existing retry semantics.

Only the health endpoint family is exposed over HTTP (`management.endpoints.web.exposure.include=health`). Spring Security additionally permits only the three exact GET paths above. Component paths such as `/actuator/health/db`, nested group components, trailing-slash variants, the `/actuator` discovery page, all other Actuator endpoints, and unrelated application routes return 403. POST, PUT, PATCH, DELETE, HEAD, and OPTIONS probe requests also return 403, including requests with valid CSRF tokens. The existing `/connect` permissions, CSRF protection, defensive headers, and no-store handling remain in place.

Both `show-details` and `show-components` are explicitly `never`. Group responses contain only status. Boot's root response also lists the public group names (`liveness`, `readiness`); it contains no component results or details. Database information, exceptions, filesystem paths, account state, and provider information are not public health data. No business health contributors or additional telemetry are added.

Datasource, Flyway, and JPA initialization still gate normal startup. Migration or database initialization failure fails startup; there is no separate Flyway health endpoint or bypass.

Production Compose separates PostgreSQL administration (`postgres`, `POSTGRES_ADMIN_PASSWORD`) from application access (`schedule_monitor`, `POSTGRES_APP_PASSWORD`). Spring/Flyway/JPA use only the non-superuser application role, which owns its database/schema for application DDL and has `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`. The admin password is not passed to Spring and neither password is baked into an image. PostgreSQL remains internal only, without a host port.

The official entrypoint runs the role initialization script only for an empty data volume. Normal restarts preserve the role and credentials; changes to the example/configuration do not change an existing volume. Volumes from the earlier superuser application configuration require explicit administrator reconciliation before reuse, not an automatic restart-time rewrite. See [container deployment](container-deployment.md#postgresql-roles-and-initialization) for the credential boundary and existing-volume semantics, and [database recovery](database-backup-restore.md) for online backups and guarded restore with readiness verification. TLS/reverse proxy remains deferred.

## Shutdown and scheduled work

Spring Boot 4.1's default graceful web-server shutdown is retained. `spring.lifecycle.timeout-per-shutdown-phase=30s` makes the lifecycle phase budget explicit. This is a per-phase wait budget, **not a 30-second limit on total process termination or bean destruction**. No custom shutdown hook is installed.

Monitoring, notification dispatch, and Telegram supervision use Spring-managed `@Scheduled` tasks. There is no separate application-owned monitoring executor. Spring stops scheduling during context closure; the existing interruption handling stops monitoring pacing/retries. Tracking and outbox writes retain their transaction boundaries. An interrupted delivery can leave a durable in-flight claim, which becomes eligible for recovery after its lease expires; delivery remains at least once, so a send completed before acknowledgement can be retried. No scheduling or delivery semantics change here.

Telegram runtime and message transport beans already declare `destroyMethod="close"`. Runtime closure closes its engine; engine cleanup shuts down its owned polling executor and HTTP dispatcher and evicts pooled connections, including when session closure fails. Existing runtime/engine tests cover resource cleanup and executor termination using fakes and loopback WireMock. The validated 65-second long-poll HTTP read timeout remains unchanged. An in-progress registration can delay runtime destruction while the synchronized start method completes; the lifecycle phase budget does not override that network timeout.

The lifecycle audit found no executor detached from Spring cleanup or unbounded executor wait requiring a refactor. Existing lifecycle logging is retained; no request/health/body/header logging or verbose logging is enabled. Monitoring, connection, and Telegram remain disabled by default.

## Verification

`OperationalHealthTests` exercises the configured Actuator groups through the real security filter chain. It verifies the actual PostgreSQL contributor type and healthy responses, substitutes a controlled `db` contributor temporarily to prove DOWN/recovery behavior without stopping the shared test database, checks exact response privacy, and verifies HTTP exposure separately from security denial. Existing connect security and outbox recovery tests remain regression coverage. Tests use synthetic data and local services only.

See [container deployment](container-deployment.md) for the image, Compose healthchecks, stop budget and isolated smoke, including [database recovery tests](database-backup-restore.md#verification). Reverse proxy/TLS and the complete production deployment runbook remain deferred.
