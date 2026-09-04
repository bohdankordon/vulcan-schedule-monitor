# Architecture

## Status and scope

Vulcan Schedule Monitor is an integrated modular monolith: secure connected accounts and encrypted sessions feed catalog-based subscriptions, account-aware monitoring/tracking, a recipient- and catalog-specific transactional outbox, and a disabled-by-default Telegram interface. Network-facing monitoring, Telegram, and browser authentication remain independently gated by configuration.

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

`VulcanSessionMaterial` is the redacted, validated secret-bearing representation used to reconstruct a session. `VulcanSession` can snapshot its current cookies for future rotation-aware persistence.

The secure connection boundary currently provides:

- one internal VULCAN account per application user with connected/reconnect-required/disconnected state;
- 256-bit URL-safe connection capabilities stored only as SHA-256 hashes, with expiry, single use, and bounded credential failures;
- a token-to-HttpOnly-cookie exchange that removes the bearer token from the normal form URL;
- a Spring MVC and Thymeleaf credential page protected by Spring Security CSRF, no-store/no-referrer/nosniff/frame-deny/CSP headers, and no third-party assets;
- strict HTTPS `vulcan.net.pl` portal validation before any browser work;
- an isolated Playwright Chromium adapter for only the currently identifiable direct username/password form, with fresh contexts and no storage files, screenshots, traces, video, or HAR;
- explicit rejection of off-domain redirects, MFA, CAPTCHA, and unsupported identity-provider flows;
- authenticated request observation for application path, Referer, verification token, AppGuid, and origin-filtered cookies without cookie-name hardcoding;
- mandatory `GetCache` plus complete `GetTree` verification outside database transactions;
- AES-256-GCM encrypted, versioned session payloads and separately encrypted opt-in remembered credentials, using fresh 96-bit nonces, 128-bit tags, and account/purpose AAD;
- atomic account/session/catalog persistence under a locked, revalidated token row;
- an account-scoped active/inactive class catalog and protocol-neutral immutable read port;
- encrypted session loading and post-request cookie-rotation persistence for monitoring; and
- one-shot account-scoped recovery through the same verifier and Playwright boundary when remembered credentials exist.

The persistence and tracking boundary currently provides:

- Flyway-owned PostgreSQL schema creation with Hibernate configured for validation only;
- a JPA adapter behind the protocol-independent `ActiveChangeStore` port;
- one production tracking scope per catalog class and Monday-to-Sunday week, retaining journal-only legacy scopes as unused history;
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
- soft-enabled monitoring subscriptions unique by application user and catalog class;
- ownership-validating catalog-based application services for enable/disable, safe class listing, and subscription checks;
- a Telegram recipient directory that returns immutable routing references without exposing JPA entities; and
- PostgreSQL constraints preventing duplicate Telegram users, private chats, and user/catalog subscriptions.

The monitoring application boundary currently provides:

- a production target provider that selects distinct account/catalog/journal targets for connected accounts, active classes/users, and enabled owned subscriptions;
- deterministic Europe/Warsaw planning of exactly two scopes per deduplicated target: current week, then next week;
- a production persisted-account weekly source that decrypts the correct session, performs the request, and encrypts cookie rotation before reconciliation;
- sequential cycle execution with minimum inter-scope pacing;
- three-attempt bounded transport/server retry with exponential backoff;
- `Retry-After` handling with process-local account-keyed gates for long rate limits;
- explicit per-scope and cycle outcomes with authentication and rate-limit isolation per account;
- a conditional five-minute Spring fixed-delay trigger with a local overlap guard; and
- tests proving failed scheduled fetches never reach reconciliation or resolve PostgreSQL state.

The monitoring trigger is disabled by default and requires secure connection infrastructure when enabled. The persisted-account source wires automatically; an empty subscription database safely produces no targets or external activity. The single-instance guard is not a distributed lock; multi-instance coordination is deployment work for a later phase.

The notification boundary currently provides:

- a Flyway V5 catalog identity extension to the recipient-specific outbox with explicit minimized columns and database invariants;
- catalog-scoped recipient resolution from enabled subscriptions owned by active users with active connected accounts;
- one baseline summary intent per active recipient or one ordered intent per recipient for each `NEW`, `UPDATED`, and `RESOLVED` transition;
- atomic enqueue inside successful tracking reconciliation;
- `PENDING`, `IN_FLIGHT`, `DELIVERED`, and `DEAD` delivery states;
- bounded PostgreSQL claims using `FOR UPDATE SKIP LOCKED`, two-minute leases, and per-claim ownership tokens;
- recovery of expired claims with an incremented attempt count;
- a protocol-independent immutable message containing internal recipient and catalog identities plus a delivery gateway contract;
- success acknowledgement, bounded exponential retry, provider `retryAfter`, permanent failure, and five-attempt exhaustion handling; and
- a programmatically callable sequential dispatcher that performs delivery outside database transactions.

Legacy actionable rows without an unambiguous recipient/account/catalog mapping migrate to `DEAD/UNROUTABLE`; historical terminal rows may remain catalog-less. New actionable rows require both an application user and catalog class. The Telegram delivery gateway resolves the private chat and authorized class label only at the adapter edge, and its scheduled dispatcher uses batch size one. Because the whole Telegram module is conditional, default startup remains independent of external notification providers and pending events may accumulate safely.

The Telegram adapter currently provides:

- TelegramBots 10.2.1 core-module integration without its Spring Boot 3-targeted starter;
- manually owned long-polling executor and OkHttp resources with explicit shutdown;
- supervised registration with 5-second, 15-second, 45-second, and capped two-minute retries;
- process-local provider deferral for structured `429 retry_after` and suspension-until-restart for `401`;
- a thin ordered update consumer, private-chat update router, and isolated command handlers;
- Telegram identity registration through the existing application service;
- `/start`, `/help`, `/status`, `/subscriptions`, `/classes`, and secure-link `/connect` replies;
- strictly parsed private inline callbacks with user/catalog ownership re-authorization and keyboard refresh;
- a minimized class-labelled notification formatter and `NotificationDeliveryGateway` implementation;
- a two-second, overlap-guarded durable dispatch trigger with a provider-specific batch size of one; and
- no-network lifecycle/context tests plus PostgreSQL-to-fake-Telegram delivery and flood-control regressions.

Direct command and callback replies are best effort and bypass the outbox. Monitoring notifications retain at-least-once semantics. Group/channel callbacks and edited/media updates are ignored, and no command accepts VULCAN credentials or arbitrary journal-ID subscription changes. `/status` and `/classes` expose only safe connection/catalog state.

## Planned modules

The planned major module boundaries are:

- **VULCAN integration** — extend the current read-only transport and mapping as verified needs arise;
- **authentication/session management** — implemented Playwright-based authorized direct login, encrypted sessions, rotation persistence, and automatic one-shot recovery;
- **schedule/change domain** — internal schedule and change concepts;
- **monitoring** — implemented successful-snapshot reconciliation and conditional, traffic-conscious scheduling foundation;
- **subscriptions** — implemented internal users, minimized Telegram identity, and monitoring preferences;
- **notification/outbox** — implemented reliable notification intent, delivery state, and generic dispatcher core;
- **Telegram adapter** — implemented private-chat interaction, class-selection callbacks, long polling, and class-labelled outbound delivery;
- **persistence** — implemented tracking-state storage adapter and transaction boundary, with future feature storage added only as required; and
- **secure account connection** — implemented careful onboarding without exposing credentials through Telegram.

The VULCAN integration, secure account connection, encrypted session store, account-scoped catalog, initial schedule/change domain, application users, subscriptions, subscription-backed target/recipient routing, persistent active-change tracking, per-recipient transactional notification intent persistence, generic durable dispatch core, and Telegram transport/runtime are implemented. The remaining items are intended boundaries, not placeholder packages.

## Technology roadmap

The project uses Java 21, Spring Boot 4.1.1, PostgreSQL, Spring Data JPA / Hibernate, Flyway, Maven, Spotless, JUnit 6, WireMock 3.13.2, Testcontainers, and GitHub Actions for verification.

The following capabilities are planned but **not implemented yet**:

- multiple accounts per application user and explicit switching/disconnect semantics;
- a durable account-level reconnect-required notification event;
- periodic catalog refresh outside connection/recovery;
- richer subject/period/teacher notification rendering;
- multi-instance scheduling and durable rate-limit coordination;
- delivered-outbox retention cleanup and observability; and
- production deployment.

The current connection adapter is synthetic-tested and requires authorized manual validation against real tenant variations; it is not a claim of universal VULCAN login compatibility.

Technology choices beyond this list will be made when a concrete feature requires them. New libraries and frameworks should use their latest stable, mutually compatible releases at the time of adoption. Spring Boot dependency management remains the default; explicit version overrides should represent a deliberate, verified project baseline.

## Security and privacy constraints

External data collection must be minimized. Telegram user and private-chat IDs are isolated in `telegram_identity`; subscriptions and outbox rows use only internal application-user IDs. Telegram usernames, names, messages, profile data, and locale are not stored. Connection tokens are hash-only. Session material and opt-in credentials are authenticated ciphertext, with the key supplied only at runtime. The catalog stores authorized class metadata but no teachers, students, staff directory, or raw tree response. Raw browser captures, personal school data, and real production responses do not belong in the repository. Protocol research uses sanitized notes rather than copied payloads. Tracking persistence stores no raw response or unknown annotation text; sensitive-derived values may affect a one-way fingerprint but canonical hash inputs are neither stored nor logged.

## Account-aware identity boundary

Flyway V5 uses `vulcan_class_catalog.id` as the durable selection, tracking, outbox, and callback identity. `vulcanAccountId` selects encrypted session/recovery state, while `journalId` remains only the account-local VULCAN protocol input. Existing ambiguous subscription/outbox rows are safely mapped or quarantined, and journal-only tracking history remains untouched and unused. See [Account-aware monitoring](account-aware-monitoring.md).
