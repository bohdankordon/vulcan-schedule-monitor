# Architecture

## Status and scope

Vulcan Schedule Monitor currently contains its minimal Spring Boot foundation and the first read-only VULCAN integration slice. This document separates implemented foundations from future architecture without prescribing unimplemented class designs.

## Architectural style

The service will begin as a modular monolith. Its current scale and deployment needs do not justify the operational and distributed-system complexity of microservices. Clear module boundaries will preserve the option to extract a component later if measured needs warrant it.

The codebase uses Java 21 and Spring Boot with feature-oriented package organization. Application and domain logic remain independent of external protocols. VULCAN, persistence, browser automation, and notification systems are exposed through adapters at the edges of the application.

VULCAN transport DTOs will remain separate from internal domain models so that changes in unofficial payloads do not propagate into business concepts. Telegram will be treated as a notification and user-interface adapter, not as a home for business logic.

## Implemented now

The read-only VULCAN boundary currently provides:

- an explicitly configured Spring `RestClient`, `JdkClientHttpRequestFactory`, and Java `HttpClient` transport;
- a per-session Java `CookieManager` seeded from a manually supplied authorized browser session;
- sanitized HTTP and protocol failures that do not include response bodies or session material;
- bootstrap parsing for the current school year and opaque lesson-period identifiers;
- recursive journal discovery from the irregular tree response;
- Monday-to-Sunday schedule retrieval using the lightweight schedule endpoint;
- response-local base/effective row correlation represented as semantic planned/effective lesson context, without exposing VULCAN row IDs;
- known teacher-substitution extraction plus explicit, transient preservation of unknown annotations with redacted diagnostics; and
- JUnit 6 unit tests and WireMock protocol integration tests using synthetic fixtures.

The current session input is temporary development plumbing. It does not log in, renew an expired session, poll continuously, or persist schedule state.

## Planned modules

The planned major module boundaries are:

- **VULCAN integration** — extend the current read-only transport and mapping as verified needs arise;
- **authentication/session management** — Playwright-based authorized login and session recovery;
- **schedule/change domain** — internal schedule and change concepts;
- **monitoring** — orchestration and traffic-conscious monitoring cadence;
- **subscriptions** — user monitoring preferences;
- **notification/outbox** — reliable notification intent and delivery state;
- **Telegram adapter** — user interaction and outbound messages;
- **persistence** — storage adapters and transaction boundaries; and
- **secure account connection** — careful onboarding without exposing credentials.

Only the VULCAN integration and initial schedule/change domain concepts are implemented. The remaining items are intended boundaries, not placeholder packages.

## Technology roadmap

The project uses Java 21, Spring Boot 4.1.1, Maven, Spotless, JUnit 6, WireMock 3.13.2 test fixtures, and GitHub Actions for verification.

The following technologies are planned but **not implemented yet** and are deliberately absent from current runtime and build dependencies unless noted otherwise:

- PostgreSQL;
- Spring Data JPA / Hibernate;
- Flyway;
- TelegramBots Java library;
- Playwright for Java;
- Docker / Docker Compose;
- Testcontainers.

The next integration stages still plan PostgreSQL persistence, Flyway migrations, a monitoring scheduler and previous/current snapshot state machine, reliable outbox delivery, Telegram, automated authentication/re-login, and production deployment.

Technology choices beyond this list will be made when a concrete feature requires them. New libraries and frameworks should use their latest stable, mutually compatible releases at the time of adoption. Spring Boot dependency management remains the default; explicit version overrides should represent a deliberate, verified project baseline.

## Security and privacy constraints

External data collection must be minimized. Credentials, session material, raw browser captures, personal data, and real production responses do not belong in the repository. Protocol research uses sanitized notes rather than copied payloads.
