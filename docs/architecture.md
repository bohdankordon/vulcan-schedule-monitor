# Architecture

## Status and scope

Vulcan Schedule Monitor currently contains its Spring Boot foundation, a read-only VULCAN integration slice, and PostgreSQL-backed active schedule-change tracking. This document separates implemented foundations from future architecture without prescribing unimplemented class designs.

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

The current session input is temporary development plumbing. It does not log in, renew an expired session, or poll continuously.

The persistence and tracking boundary currently provides:

- Flyway-owned PostgreSQL schema creation with Hibernate configured for validation only;
- a JPA adapter behind the protocol-independent `ActiveChangeStore` port;
- one tracking scope per journal and Monday-to-Sunday week;
- a no-spam baseline on the first successful snapshot, including empty baselines;
- semantic, versioned SHA-256 `changeKey` and `fingerprint` values without VULCAN row IDs;
- transactional `NEW`, `UPDATED`, and `RESOLVED` reconciliation over current active state;
- pessimistic locking for existing scopes, database-enforced first-scope uniqueness, and optimistic version metadata;
- a coordinator boundary that never reconciles after a failed fetch; and
- PostgreSQL/Testcontainers coverage for migrations, Hibernate validation, constraints, lifecycle behavior, and rollback.

Only current active changes are retained. Resolved rows are removed after their transition is produced; durable delivery history belongs to the planned transactional outbox.

## Planned modules

The planned major module boundaries are:

- **VULCAN integration** — extend the current read-only transport and mapping as verified needs arise;
- **authentication/session management** — Playwright-based authorized login and session recovery;
- **schedule/change domain** — internal schedule and change concepts;
- **monitoring** — implemented successful-snapshot reconciliation; traffic-conscious scheduled cadence remains planned;
- **subscriptions** — user monitoring preferences;
- **notification/outbox** — reliable notification intent and delivery state;
- **Telegram adapter** — user interaction and outbound messages;
- **persistence** — implemented tracking-state storage adapter and transaction boundary, with future feature storage added only as required; and
- **secure account connection** — careful onboarding without exposing credentials.

The VULCAN integration, initial schedule/change domain, and persistent active-change tracking are implemented. The remaining items are intended boundaries, not placeholder packages.

## Technology roadmap

The project uses Java 21, Spring Boot 4.1.1, PostgreSQL, Spring Data JPA / Hibernate, Flyway, Maven, Spotless, JUnit 6, WireMock 3.13.2, Testcontainers, and GitHub Actions for verification.

The following capabilities are planned but **not implemented yet**:

- actual scheduler and poll cadence;
- HTTP 429 backoff and session recovery;
- transactional notification outbox;
- TelegramBots Java library;
- Playwright for Java;
- user subscriptions; and
- production deployment.

The next integration stages still plan a monitoring scheduler, reliable outbox delivery, Telegram, automated authentication/re-login, user subscriptions, and production deployment. The current database layer is a foundation for those features, not a claim of production readiness.

Technology choices beyond this list will be made when a concrete feature requires them. New libraries and frameworks should use their latest stable, mutually compatible releases at the time of adoption. Spring Boot dependency management remains the default; explicit version overrides should represent a deliberate, verified project baseline.

## Security and privacy constraints

External data collection must be minimized. Credentials, session material, raw browser captures, personal data, and real production responses do not belong in the repository. Protocol research uses sanitized notes rather than copied payloads. Tracking persistence stores no raw response or unknown annotation text; sensitive-derived values may affect a one-way fingerprint but canonical hash inputs are neither stored nor logged.
