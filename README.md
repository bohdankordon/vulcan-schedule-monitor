# Vulcan Schedule Monitor

[![CI](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml/badge.svg)](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An unofficial Java service for monitoring school schedule changes available through the VULCAN system and delivering timely notifications.

> **Disclaimer:** This is an unofficial project and is not affiliated with or endorsed by VULCAN sp. z o.o.

## Status

The project is in early development. Its read-only integration slice can use an already authenticated browser session to discover journals and retrieve a synthetic-tested weekly schedule. Successful weekly snapshots establish a PostgreSQL-backed baseline and reconcile current schedule changes as `NEW`, `UPDATED`, or `RESOLVED`. A disabled-by-default monitoring foundation can plan and safely execute current- and next-week refreshes when application code explicitly supplies both a target provider and an authorized weekly source. Automated authentication, production subscriptions, APIs, and notifications are not implemented yet.

## Purpose and planned capabilities

The project aims to provide a privacy-conscious service that can:

- establish and maintain an authorized VULCAN session;
- retrieve schedules while minimizing traffic to VULCAN systems;
- identify meaningful schedule changes;
- manage monitoring subscriptions;
- persist state reliably; and
- deliver timely notifications through adapters such as Telegram.

## Architecture

Vulcan Schedule Monitor is a modular monolith with feature-oriented packages. Its VULCAN adapter translates browser-observed payloads into small internal schedule and change models. Tracking logic uses a persistence port; JPA entities remain internal to the PostgreSQL adapter.

See [Architecture](docs/architecture.md), [Monitoring orchestration](docs/monitoring.md), [Persistent change tracking](docs/change-tracking.md), [Unofficial VULCAN protocol notes](docs/vulcan-protocol.md), and [Manual session setup](docs/manual-session.md).

## Technology

- Java 21
- Spring Boot 4.1.1
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
```

No database credentials or environment-specific URLs are stored in the repository. Docker is required only to run the PostgreSQL integration tests locally.

Monitoring is off unless `vulcan.monitoring.enabled=true` is set. Enabling it also requires application-provided `MonitoringTargetProvider` and `WeeklyScheduleSource` beans; the repository deliberately provides neither a production subscription provider nor an automatically constructed VULCAN session. The default polling interval is `PT5M`.

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

The project follows data-minimization and least-privilege principles. Persistent tracking state contains only versioned hashes, change type, lesson slot identifiers, group/subject identifiers, and timestamps. Raw VULCAN responses, unknown annotations, teacher identifiers, replacement codes, credentials, cookies, session state, HAR captures, tenant identifiers, and personal school data are not stored in the tracking database or committed. Browser captures used for protocol research remain outside this repository.

Review [SECURITY.md](SECURITY.md) before reporting a vulnerability and [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.

## License

Licensed under the [MIT License](LICENSE).
