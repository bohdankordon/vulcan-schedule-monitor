# Vulcan Schedule Monitor

[![CI](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml/badge.svg)](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An unofficial Java 21 / Spring Boot service that monitors school schedule changes available through Poland's VULCAN system and delivers Telegram notifications. It keeps per-class, per-week state so it can distinguish new, updated, and resolved changes across successful checks.

> **Disclaimer:** This is an unofficial project and is not affiliated with or endorsed by VULCAN sp. z o.o.

## Status

Actively developed, privately deployed, and production-tested on an intentionally small, single-host setup. This is not a public SaaS or an official VULCAN integration. The connection, monitoring, reconciliation, Telegram delivery, and container operations described below are implemented; provider-facing features are disabled by default.

Current boundaries: one VULCAN account per application user; the supported login is a direct VULCAN-hosted username/password flow, with no MFA, CAPTCHA, or external identity-provider support. Scheduling and rate-limit gates are process-local, so the deployment is not designed for multiple application instances. See [Secure VULCAN connection](docs/vulcan-connection.md) and [Monitoring orchestration](docs/monitoring.md) for these limits.

## Highlights

- **Secure account connection:** A short-lived HTTPS `/connect` link starts Playwright-assisted login; the server verifies the resulting session and stores it encrypted, with optional separately encrypted remembered credentials.
- **Stateful monitoring:** Authorized class subscriptions drive current- and next-week checks. Deterministic PostgreSQL reconciliation establishes a baseline, then emits `NEW`, `UPDATED`, and `RESOLVED` transitions.
- **Reliable notifications:** Tracking changes and recipient-specific outbox intents commit together. Leased PostgreSQL claims, retries, and recovery support at-least-once Telegram delivery.
- **Failure isolation:** Bounded fetch retries, account-scoped rate-limit handling, cookie rotation, and one automatic session recovery attempt where remembered credentials are available.
- **Private Telegram interface:** Class selection, connection/status commands, and notifications; bot text and menus support English, Polish, Russian, and Ukrainian.
- **Verified operations:** PostgreSQL/Testcontainers and WireMock tests, a Docker image with Chromium, a Caddy HTTPS edge, health probes, and backup/restore tooling.

## Demo / screenshots

Public screenshots are pending safe demo captures. The useful views are a Telegram `/classes` selection, a notification with `/status`, and the HTTPS `/connect` form. Any published captures must use synthetic class/account data and omit credentials, tokens, personal data, and deployment URLs.

## Architecture

The application is a modular monolith: VULCAN, Telegram, browser automation, and PostgreSQL sit behind adapters; monitoring and change tracking use internal models and ports.

```text
Private Telegram chat --> Telegram adapter --> subscriptions / connect link
                                                   |
Browser -- HTTPS/Caddy --> Spring Boot connect flow -- Playwright --> VULCAN
                                                   |
                                           encrypted session + class catalog
                                                   |
Spring Boot monitoring --> VULCAN weekly fetch --> reconciliation
                                                   |
                                      PostgreSQL: active state + outbox
                                                   |
                                        outbox dispatcher --> Telegram delivery
```

Caddy is the HTTPS edge in the container deployment. Monitoring fetches schedules through the unofficial VULCAN adapter; only successfully fetched snapshots enter reconciliation. See [Architecture](docs/architecture.md) and [Account-aware monitoring](docs/account-aware-monitoring.md).

## What it does

An authorized user connects one VULCAN account through `/connect`, then selects classes in a private Telegram chat. The scheduler checks each subscribed class for the current and next Monday-to-Sunday week. A successful first snapshot establishes a no-spam baseline; later successful snapshots produce semantic `NEW`, `UPDATED`, and `RESOLVED` transitions. A failed or rate-limited provider request never means “empty schedule” and never resolves existing changes. [Tracking design](docs/change-tracking.md) · [Monitoring design](docs/monitoring.md)

Each successful state transition and its per-recipient delivery intent are committed in one PostgreSQL transaction. The outbox dispatcher claims due rows with leases, acknowledges delivery, and retries eligible failures. Delivery is **at least once**: a send accepted by Telegram before acknowledgement can be repeated after a crash. Direct bot command replies are best effort and do not use the outbox. [Outbox design](docs/notification-outbox.md)

The connection link is a short-lived, single-use capability. Playwright captures an authenticated session in an isolated browser context; server-side VULCAN checks verify it before persistence. Session material and opt-in remembered credentials are encrypted with AES-256-GCM. Normal cookie rotation is persisted, while supported authentication failures can trigger one recovery attempt when remembered credentials exist. VULCAN credentials are entered only in the HTTPS form, never in Telegram. [Connection and security details](docs/vulcan-connection.md)

Deferred work includes multiple VULCAN accounts per user, periodic catalog refresh outside connection/recovery, a durable reconnect-required alert, multi-instance coordination, and delivered-outbox retention cleanup. These are not current capabilities.

## Technology

Java 21, Spring Boot 4.1.1 (MVC, Security, Actuator, Thymeleaf), PostgreSQL 18 with Spring Data JPA and Flyway, Playwright Java, TelegramBots long polling, and Caddy/Docker Compose. Verification uses JUnit 6, PostgreSQL Testcontainers, WireMock, a local Playwright browser regression, and GitHub Actions. The Maven Wrapper pins Maven 3.9.16; Spotless checks Java formatting.

## Requirements and configuration

- Java 21 JDK and a reachable PostgreSQL database. The Maven Wrapper supplies Maven.
- Docker for PostgreSQL integration tests; Docker Desktop plus PowerShell for the Windows local runner.
- Chromium matching the Playwright dependency when secure connection runs outside the container; the production image includes it.

For a direct application run, configure the datasource through standard Spring properties (for example `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD`). Enable only the integrations you need:

| Feature | Default | Required when enabled |
| --- | --- | --- |
| Telegram | `telegram.bot.enabled=false` | `TELEGRAM_BOT_TOKEN` |
| Secure connection | `vulcan.connection.enabled=false` | HTTPS `vulcan.connection.public-base-url`, `VULCAN_MASTER_KEY` (Base64 of exactly 32 random bytes), and Chromium |
| Monitoring | `vulcan.monitoring.enabled=false` | Secure connection enabled and authorized subscriptions |

Monitoring polls every five minutes by default; no subscriptions means no VULCAN schedule requests. In production Compose, the corresponding switches are `TELEGRAM_ENABLED`, `VULCAN_CONNECTION_ENABLED`, and `VULCAN_MONITORING_ENABLED`; its private environment file also requires database passwords and the stable master key. Keep secrets outside Git. See [Connection configuration](docs/vulcan-connection.md), [Telegram configuration](docs/telegram.md), and [Container deployment](docs/container-deployment.md).

## Local development

On Windows, with Java 21 and Docker Desktop, run from the repository root:

```powershell
.\scripts\dev.ps1
```

The runner starts local PostgreSQL, installs the matching Chromium build, and starts Spring Boot. It prompts for the Telegram bot token without echoing it and protects that token and the local VULCAN key with Windows DPAPI under ignored `.dev/`. Secure connection and Telegram are enabled; monitoring stays off until the account and class catalog have been checked. To enable monitoring after that check:

```powershell
.\scripts\dev.ps1 -EnableMonitoring
```

Use `.\scripts\dev.ps1 -Help` for other options. `-ResetDevState` deletes the local database and protected secrets after an explicit `RESET` confirmation. DPAPI storage is a local development convenience, not production secret storage. For manual startup on another OS, provide PostgreSQL and the required feature configuration above; see [Manual session setup](docs/manual-session.md).

## Build and test

```shell
./mvnw -B -ntp verify
```

On Windows use `.\mvnw.cmd -B -ntp verify`. This runs the Java test suite and Spotless check; PostgreSQL integration tests need Docker. Apply Java formatting with `./mvnw spotless:apply` (Windows: `.\mvnw.cmd spotless:apply`). CI also runs an isolated container/HTTPS/database-recovery smoke and a local Playwright browser regression; neither requires a live VULCAN account. [.github/workflows/ci.yml](.github/workflows/ci.yml)

## Production and operations

The single-host Compose stack runs Spring Boot and PostgreSQL behind a Caddy HTTPS edge. Only Caddy publishes host ports; PostgreSQL data and Caddy TLS state use persistent volumes. Health endpoints distinguish liveness from database-backed readiness. Backup and guarded restore scripts cover the PostgreSQL database; the encryption key must be preserved separately. Provider integrations start disabled and are enabled deliberately after setup.

Start with [Container deployment](docs/container-deployment.md), then use [HTTPS edge](docs/https-reverse-proxy.md), [Operational health](docs/operations.md), [Database backup and restore](docs/database-backup-restore.md), and the [single-host deployment runbook](docs/acer-server-deployment.md). Restoring a backup rolls the database back in time and can cause a previously sent notification to be delivered again.

## Security and privacy

The application stores only the identifiers needed for account ownership, subscriptions, and delivery. Telegram routing identifiers live in a separate identity table; outbox rows use internal recipient IDs. Connection capabilities are stored as hashes, and session material plus opt-in credentials are encrypted with a runtime key. Raw VULCAN responses, browser captures, plaintext credentials, student/teacher data, and Telegram message bodies are not persisted as application state. Keep real provider data and production identifiers out of issues, screenshots, and commits. Read [SECURITY.md](SECURITY.md) before reporting a vulnerability.

## Documentation

- **Product flow:** [Secure connection](docs/vulcan-connection.md), [Subscriptions](docs/subscriptions.md), [Telegram adapter](docs/telegram.md).
- **Backend design:** [Architecture](docs/architecture.md), [Account-aware monitoring](docs/account-aware-monitoring.md), [Monitoring](docs/monitoring.md), [Change tracking](docs/change-tracking.md), [Notification outbox](docs/notification-outbox.md).
- **Integration and operations:** [Unofficial VULCAN protocol notes](docs/vulcan-protocol.md), [Container deployment](docs/container-deployment.md), [HTTPS edge](docs/https-reverse-proxy.md), [Health](docs/operations.md), [Backup/restore](docs/database-backup-restore.md), [Single-host runbook](docs/acer-server-deployment.md).
- **Contributing:** [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Licensed under the [MIT License](LICENSE).
