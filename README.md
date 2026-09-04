# Vulcan Schedule Monitor

[![CI](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml/badge.svg)](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An unofficial Java service for monitoring school schedule changes available through the VULCAN system and delivering timely notifications.

> **Disclaimer:** This is an unofficial project and is not affiliated with or endorsed by VULCAN sp. z o.o.

## Status

The project is in early development. Its read-only integration slice can use an authenticated browser session to discover journals and retrieve a synthetic-tested weekly schedule. Successful weekly snapshots establish a PostgreSQL-backed baseline and reconcile current schedule changes as `NEW`, `UPDATED`, or `RESOLVED`. Application users can hold minimized Telegram routing identities and enable journal subscriptions. A disabled-by-default TelegramBots adapter supports safe private-chat commands and recipient-specific outbox delivery. Phase 7 adds a disabled-by-default secure HTTPS VULCAN connection flow, Playwright direct-login adapter, AES-256-GCM session persistence, optional encrypted remembered credentials, and an account-scoped class catalog. Connected accounts are deliberately not a monitoring source yet; account-aware subscription identity and class selection belong to Phase 8.

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

See [Architecture](docs/architecture.md), [Secure VULCAN connection](docs/vulcan-connection.md), [Telegram adapter](docs/telegram.md), [Subscriptions](docs/subscriptions.md), [Monitoring orchestration](docs/monitoring.md), [Persistent change tracking](docs/change-tracking.md), [Notification outbox](docs/notification-outbox.md), [Unofficial VULCAN protocol notes](docs/vulcan-protocol.md), and [Manual session setup](docs/manual-session.md).

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

Monitoring is off unless `vulcan.monitoring.enabled=true` is set. The production `MonitoringTargetProvider` reads active subscriptions, but enabling monitoring still requires an application-provided `WeeklyScheduleSource` backed by an authorized VULCAN session. The default polling interval is `PT5M`.

Telegram is off unless `telegram.bot.enabled=true` is set. Disabled startup creates no Telegram runtime, client, delivery gateway, or dispatch scheduler and requires no token. When enabled, the adapter uses long polling and dispatches at most one durable intent per two-second scheduler tick. Supported private-chat commands are `/start`, `/help`, `/status`, `/subscriptions`, and `/connect`. When secure connection is enabled, `/connect` issues a fresh short-lived link to the configured public HTTPS origin. Never send VULCAN credentials through Telegram.

Secure connection is off unless `vulcan.connection.enabled=true`. Enabling it also requires `vulcan.connection.public-base-url` and a valid `VULCAN_MASTER_KEY`. Install Chromium manually on the runtime host; builds and CI do not download or launch a browser:

```powershell
.\mvnw.cmd -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium" exec:java
```

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
