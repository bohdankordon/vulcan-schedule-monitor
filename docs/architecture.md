# Architecture

## Status and scope

Vulcan Schedule Monitor currently contains only a minimal Spring Boot application, a context-load test, build-quality tooling, and repository automation. This document records agreed architectural direction without prescribing unimplemented class designs.

## Architectural style

The service will begin as a modular monolith. Its current scale and deployment needs do not justify the operational and distributed-system complexity of microservices. Clear module boundaries will preserve the option to extract a component later if measured needs warrant it.

The codebase will use Java 21 and Spring Boot with feature-oriented package organization. Application and domain logic will remain independent of external protocols. VULCAN, persistence, browser automation, and notification systems will be exposed through adapters at the edges of the application.

VULCAN transport DTOs will remain separate from internal domain models so that changes in unofficial payloads do not propagate into business concepts. Telegram will be treated as a notification and user-interface adapter, not as a home for business logic.

## Planned modules

The planned major module boundaries are:

- **VULCAN integration** — read-only protocol transport and DTO mapping;
- **authentication/session management** — authorized session lifecycle;
- **schedule/change domain** — internal schedule and change concepts;
- **monitoring** — orchestration and traffic-conscious monitoring cadence;
- **subscriptions** — user monitoring preferences;
- **notification/outbox** — reliable notification intent and delivery state;
- **Telegram adapter** — user interaction and outbound messages;
- **persistence** — storage adapters and transaction boundaries; and
- **secure account connection** — careful onboarding without exposing credentials.

These are intended boundaries, not packages or implementations present in the bootstrap.

## Technology roadmap

The bootstrap already uses Java 21, Spring Boot 4.1.1, JUnit 6 for a context test, Maven, Spotless, and GitHub Actions for verification.

The following technologies are planned but **not implemented yet** and are deliberately absent from current runtime and build dependencies unless noted otherwise:

- PostgreSQL;
- Spring Data JPA / Hibernate;
- Flyway;
- TelegramBots Java library;
- Playwright for Java;
- Docker / Docker Compose;
- WireMock; and
- Testcontainers.

JUnit 6 and GitHub Actions are established at bootstrap level only. Broader unit/integration test suites and delivery workflows will grow alongside implemented features.

Technology choices beyond this list will be made when a concrete feature requires them. New libraries and frameworks should use their latest stable, mutually compatible releases at the time of adoption. Spring Boot dependency management remains the default; explicit version overrides should represent a deliberate, verified project baseline.

## Security and privacy constraints

External data collection must be minimized. Credentials, session material, raw browser captures, personal data, and real production responses do not belong in the repository. Protocol research uses sanitized notes rather than copied payloads.
