# Vulcan Schedule Monitor

[![CI](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml/badge.svg)](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An unofficial Java service for monitoring school schedule changes available through the VULCAN system and delivering timely notifications.

> **Disclaimer:** This is an unofficial project and is not affiliated with or endorsed by VULCAN sp. z o.o.

## Status

The project is in early development. A disabled-by-default secure HTTPS flow connects one authorized VULCAN account per application user, encrypts its session and optional remembered credentials, and maintains an account-scoped class catalog. Catalog-based subscriptions feed account-aware current/next-week monitoring; successful snapshots establish PostgreSQL baselines and reconcile `NEW`, `UPDATED`, or `RESOLVED` changes into a recipient-specific durable outbox. Normal fetches persist cookie rotation, authentication failures can recover once with remembered credentials, and failures/rate limits are isolated per account. The Telegram adapter provides private `/classes` selection and human-readable class-labelled delivery without exposing protocol IDs.

## Purpose and planned capabilities

The project aims to provide a privacy-conscious service that can:

- establish and maintain an authorized VULCAN session;
- retrieve schedules while minimizing traffic to VULCAN systems;
- identify meaningful schedule changes;
- manage monitoring subscriptions;
- persist state reliably; and
- deliver timely notifications through adapters such as Telegram.

## Architecture

Vulcan Schedule Monitor is a modular monolith with feature-oriented packages. Its VULCAN adapter translates browser-observed payloads into small internal schedule and change models. Tracking and notification logic use protocol-independent ports; JPA entities remain internal to PostgreSQL adapters.

See [Architecture](docs/architecture.md), [Account-aware monitoring](docs/account-aware-monitoring.md), [Secure VULCAN connection](docs/vulcan-connection.md), [Telegram adapter](docs/telegram.md), [Subscriptions](docs/subscriptions.md), [Monitoring orchestration](docs/monitoring.md), [Persistent change tracking](docs/change-tracking.md), [Notification outbox](docs/notification-outbox.md), [Unofficial VULCAN protocol notes](docs/vulcan-protocol.md), and [Manual session setup](docs/manual-session.md).

## Technology

- Java 21
- Spring Boot 4.1.1
- Spring MVC, Thymeleaf, and Spring Security
- Playwright Java 1.62.0
- TelegramBots 10.2.1 core long-polling and client modules
- Spring RestClient backed by Java 21 HttpClient
- PostgreSQL with Spring Data JPA / Hibernate
- Flyway-owned database migrations
- Testcontainers PostgreSQL integration tests
- Maven 3.9.16 through Maven Wrapper
- JUnit 6
- WireMock 3.13.2 for protocol integration tests
- Spotless formatting checks
- GitHub Actions

## Requirements

- A Java 21 JDK
- PostgreSQL configured through standard Spring datasource properties
- No system Maven installation is required

Set these environment variables when running the application:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:<port>/<database>
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<password>
TELEGRAM_BOT_TOKEN=<bot-token>
VULCAN_MASTER_KEY=<Base64-of-exactly-32-random-bytes>
```

No database credentials, bot tokens, encryption keys, or environment-specific URLs are stored in the repository. `TELEGRAM_BOT_TOKEN` is required only when `telegram.bot.enabled=true`. `VULCAN_MASTER_KEY` is required only when `vulcan.connection.enabled=true`. Docker is required only to run the PostgreSQL integration tests locally.

Monitoring is off unless `vulcan.monitoring.enabled=true` is set and secure connection is enabled. The production source loads each target's encrypted account session automatically; an empty subscription set makes no VULCAN request. The default polling interval is `PT5M`.

Telegram is off unless `telegram.bot.enabled=true` is set. Disabled startup creates no Telegram runtime, client, delivery gateway, or dispatch scheduler and requires no token. When enabled, the adapter uses long polling and dispatches at most one durable intent per two-second scheduler tick. Supported private-chat commands are `/start`, `/help`, `/status`, `/subscriptions`, `/classes`, and `/connect`. `/classes` uses strictly authorized catalog callbacks; `/connect` issues a fresh short-lived link to the configured public HTTPS origin. Never send VULCAN credentials through Telegram.

Secure connection is off unless `vulcan.connection.enabled=true`. Enabling it also requires `vulcan.connection.public-base-url` and a valid `VULCAN_MASTER_KEY`. Install Chromium manually on the runtime host; builds and CI do not download or launch a browser:

```powershell
.\mvnw.cmd -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium" exec:java
```

## Local development

Windows developers with Docker Desktop and a Java 21 JDK can start the complete local stack from the repository root:

```powershell
.\scripts\dev.ps1
```

The script resolves every project path from its own location, so it does not depend on the caller's current working directory when invoked through another relative or absolute path.

On first use, the runner creates a stable VULCAN encryption key, asks once for the Telegram bot token without echoing it, protects both secrets with Windows DPAPI for the current user, starts a persistent PostgreSQL 18.6 container bound only to `127.0.0.1:54329`, installs the compatible Playwright Chromium build, and starts Spring Boot in the foreground. Telegram and the secure `/connect` flow are enabled, while automatic monitoring remains off for the initial authorized connection and `/classes` catalog smoke test. VULCAN credentials and the portal URL are entered only through `/connect`, never through the script.

Later runs reuse the named PostgreSQL volume and the protected files under the ignored `.dev/` directory. DPAPI data is bound to the same Windows machine/user profile and is a local development convenience, not production secret storage. PostgreSQL remains running when the application stops.

After the connection/catalog flow has been validated, monitoring can be enabled explicitly:

```powershell
.\scripts\dev.ps1 -EnableMonitoring
```

Replace the protected Telegram token with `.\scripts\dev.ps1 -ReconfigureTelegram`. To intentionally delete both the local database and protected secrets, run `.\scripts\dev.ps1 -ResetDevState` and type the requested `RESET` confirmation. Use `.\scripts\dev.ps1 -Help` for all runner options. The reset is coupled so a new encryption key is never silently used with ciphertext from the previous database.

## Build and test

On Linux or macOS:

```shell
./mvnw -B -ntp verify
```

On Windows:

```powershell
.\mvnw.cmd -B -ntp verify
```

Apply Java formatting before committing:

```shell
./mvnw spotless:apply
```

On Windows, use `.\mvnw.cmd spotless:apply`.

## Security and privacy

The project follows data-minimization and least-privilege principles. Telegram user and private-chat identifiers are necessary routing data and exist only in `telegram_identity`; the outbox stores only an internal application-user ID. No Telegram usernames, names, message bodies, profile data, or locale are persisted. VULCAN connection tokens are stored only as SHA-256 hashes. Session material and opt-in remembered credentials are stored only as versioned AES-256-GCM ciphertext with account- and purpose-bound AAD. Raw VULCAN responses, students, teachers, staff directories, browser artifacts, HAR captures, plaintext credentials, cookies, verification tokens, AppGuid values, tenant URLs, and encryption keys are not persisted in plaintext or committed.

Review [SECURITY.md](SECURITY.md) before reporting a vulnerability and [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.

## License

Licensed under the [MIT License](LICENSE).
