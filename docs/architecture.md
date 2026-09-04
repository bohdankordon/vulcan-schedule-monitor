# Architecture

## Status and scope

Vulcan Schedule Monitor currently contains its Spring Boot foundation, a read-only VULCAN integration slice, PostgreSQL-backed users and monitoring subscriptions, active schedule-change tracking, a per-recipient transactional notification outbox with a generic durable dispatcher core, and a disabled-by-default scheduled monitoring foundation. This document separates implemented foundations from future architecture without prescribing unimplemented class designs.

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

The current session input is temporary development plumbing. It does not log in or renew an expired session.

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

Only current active changes are retained in tracking state. Resolved rows are removed after their transition is produced; their minimized notification intent remains durable in the implemented outbox.

The user and subscription boundary currently provides:

- minimal internal application users with active state and timestamps;
- a one-to-one Telegram identity containing only `telegram_user_id` and `private_chat_id`;
- atomic Telegram identity registration/update that reuses and, when needed, reactivates the existing application user;
- soft-enabled monitoring subscriptions unique by application user and journal;
- application services for registration, enable/disable, active subscription listing, and subscription checks;
- a Telegram recipient directory that returns immutable routing references without exposing JPA entities; and
- PostgreSQL constraints preventing duplicate Telegram users, private chats, and user/journal subscriptions.

The monitoring application boundary currently provides:

- a production subscription-backed target provider that selects distinct journals having an enabled subscription owned by an active user;
- deterministic Europe/Warsaw planning of exactly two scopes per deduplicated target: current week, then next week;
- a constructible `VulcanWeeklyScheduleSource` for an already authorized `VulcanClient`;
- sequential cycle execution with minimum inter-scope pacing;
- three-attempt bounded transport/server retry with exponential backoff;
- `Retry-After` handling, including a process-local `notBefore` gate for long rate limits;
- explicit per-scope and cycle outcomes with auth and rate-limit stop behavior;
- a conditional five-minute Spring fixed-delay trigger with a local overlap guard; and
- tests proving failed scheduled fetches never reach reconciliation or resolve PostgreSQL state.

The monitoring trigger is disabled by default. Enabling it without an application-provided weekly-source adapter fails context wiring clearly; an empty subscription database safely produces no targets. The single-instance guard is not a distributed lock; multi-instance coordination is deployment work for a later phase.

The notification boundary currently provides:

- a Flyway V3 recipient extension to the V2 `notification_outbox` table with explicit minimized columns and database invariants;
- internal recipient resolution from enabled subscriptions owned by active users;
- one baseline summary intent per active recipient or one ordered intent per recipient for each `NEW`, `UPDATED`, and `RESOLVED` transition;
- atomic enqueue inside successful tracking reconciliation;
- `PENDING`, `IN_FLIGHT`, `DELIVERED`, and `DEAD` delivery states;
- bounded PostgreSQL claims using `FOR UPDATE SKIP LOCKED`, two-minute leases, and per-claim ownership tokens;
- recovery of expired claims with an incremented attempt count;
- a protocol-independent immutable message containing an internal recipient user ID and a delivery gateway contract;
- success acknowledgement, bounded exponential retry, provider `retryAfter`, permanent failure, and five-attempt exhaustion handling; and
- a programmatically callable sequential dispatcher that performs delivery outside database transactions.

Legacy V2 `PENDING` or `IN_FLIGHT` rows have no recipient and migrate to `DEAD/UNROUTABLE`; historical terminal rows may remain recipient-less. New actionable rows must reference an application user. No production delivery gateway or dispatcher scheduler is registered. Normal startup and monitoring therefore remain independent of external notification providers; pending events may accumulate safely.

## Planned modules

The planned major module boundaries are:

- **VULCAN integration** — extend the current read-only transport and mapping as verified needs arise;
- **authentication/session management** — Playwright-based authorized login and session recovery;
- **schedule/change domain** — internal schedule and change concepts;
- **monitoring** — implemented successful-snapshot reconciliation and conditional, traffic-conscious scheduling foundation;
- **subscriptions** — implemented internal users, minimized Telegram identity, and monitoring preferences;
- **notification/outbox** — implemented reliable notification intent, delivery state, and generic dispatcher core;
- **Telegram adapter** — user interaction and outbound messages;
- **persistence** — implemented tracking-state storage adapter and transaction boundary, with future feature storage added only as required; and
- **secure account connection** — careful onboarding without exposing credentials.

The VULCAN integration, initial schedule/change domain, application users, subscriptions, subscription-backed target/recipient routing, persistent active-change tracking, per-recipient transactional notification intent persistence, and generic durable dispatch core are implemented. The remaining items are intended boundaries, not placeholder packages.

## Technology roadmap

The project uses Java 21, Spring Boot 4.1.1, PostgreSQL, Spring Data JPA / Hibernate, Flyway, Maven, Spotless, JUnit 6, WireMock 3.13.2, Testcontainers, and GitHub Actions for verification.

The following capabilities are planned but **not implemented yet**:

- production session adapter;
- automatic session recovery;
- TelegramBots Java library;
- Telegram command/update router, formatter, and delivery gateway;
- dispatcher scheduling;
- Playwright for Java;
- class-selection UX using discovered VULCAN journals; and
- production deployment.

The next integration stage adds the Telegram adapter and delivery gateway without changing durable delivery ownership. Later stages still plan dispatcher scheduling, automated authentication/re-login, VULCAN connect/login and class-selection UX, multi-instance monitoring coordination, durable account-level rate limiting, and production deployment. Delivered-row retention and cleanup policy also remain future work. The current database and scheduling layers are foundations for those features, not a claim that the bot works yet.

Technology choices beyond this list will be made when a concrete feature requires them. New libraries and frameworks should use their latest stable, mutually compatible releases at the time of adoption. Spring Boot dependency management remains the default; explicit version overrides should represent a deliberate, verified project baseline.

## Security and privacy constraints

External data collection must be minimized. Telegram user and private-chat IDs are isolated in `telegram_identity`; subscriptions and outbox rows use only internal application-user IDs. Telegram usernames, names, messages, profile data, and locale are not stored. Credentials, session material, raw browser captures, personal school data, and real production responses do not belong in the repository. Protocol research uses sanitized notes rather than copied payloads. Tracking persistence stores no raw response or unknown annotation text; sensitive-derived values may affect a one-way fingerprint but canonical hash inputs are neither stored nor logged.
