# Vulcan Schedule Monitor

[![CI](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml/badge.svg)](https://github.com/bohdankordon/vulcan-schedule-monitor/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)](https://adoptium.net/temurin/releases/?version=21)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An unofficial Java service for monitoring school schedule changes available through the VULCAN system and delivering timely notifications.

> **Disclaimer:** This is an unofficial project and is not affiliated with or endorsed by VULCAN sp. z o.o.

## Status

The project is in early development. Its first read-only integration slice can use an already authenticated browser session to discover journals and retrieve a synthetic-tested weekly schedule. Automated authentication, continuous monitoring, persistence, APIs, and notifications are not implemented yet.

## Purpose and planned capabilities

The project aims to provide a privacy-conscious service that can:

- establish and maintain an authorized VULCAN session;
- retrieve schedules while minimizing traffic to VULCAN systems;
- identify meaningful schedule changes;
- manage monitoring subscriptions;
- persist state reliably; and
- deliver timely notifications through adapters such as Telegram.

## Architecture

Vulcan Schedule Monitor is a modular monolith with feature-oriented packages. Its VULCAN adapter translates browser-observed payloads into small internal schedule and change models; future persistence and notification providers will remain behind their own adapters.

See [Architecture](docs/architecture.md), [Unofficial VULCAN protocol notes](docs/vulcan-protocol.md), and [Manual session setup](docs/manual-session.md).

## Technology

- Java 21
- Spring Boot 4.1.1
- Spring RestClient backed by Java 21 HttpClient
- Maven 3.9.16 through Maven Wrapper
- JUnit 6
- WireMock 3.13.2 for protocol integration tests
- Spotless formatting checks
- GitHub Actions

Future technology choices are documented in the architecture roadmap and are not dependencies of the current application.

## Requirements

- A Java 21 JDK
- No system Maven installation is required

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

The project follows data-minimization and least-privilege principles. Credentials, cookies, session state, HAR captures, tenant identifiers, and personal school data must never be committed. Browser captures used for protocol research remain outside this repository.

Review [SECURITY.md](SECURITY.md) before reporting a vulnerability and [CONTRIBUTING.md](CONTRIBUTING.md) before contributing.

## License

Licensed under the [MIT License](LICENSE).
