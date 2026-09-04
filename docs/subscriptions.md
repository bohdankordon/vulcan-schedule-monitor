# Users and monitoring subscriptions

## Internal users and Telegram identity

`app_user` is the protocol-neutral service identity. It contains only its key, active state, and timestamps. `telegram_identity` separately stores the minimum private-delivery mapping: one Telegram user ID and one private chat ID. Usernames, profile names, locale, and message bodies are not persisted.

Registration uses the exact private-chat sender and chat values, reuses an existing identity, and reactivates its application user when necessary. Telegram identifiers never enter subscription, tracking, catalog, or notification-outbox rows.

## Catalog-based subscription identity

The durable selection identity is `vulcan_class_catalog.id`, not an external journal ID. A catalog row already binds an account-local journal to the user's single connected VULCAN account. `monitoring_subscription` is therefore unique by `(app_user_id, catalog_class_id)` and an enabled row must have a catalog class.

`MonitoringSubscriptionService` accepts catalog class IDs and returns immutable safe views containing the catalog ID for internal callback construction plus class name, optional school unit, school year, selection state, and timestamps. It never returns JPA entities and callers do not supply journal IDs.

Enabling validates that the catalog row exists, belongs to the requesting user's account, is active, and belongs to a `CONNECTED` account. Repeated enable is idempotent. Cross-user, stale, inactive, and tampered IDs cannot create a row. Disabling an owned row is idempotent and is allowed even when its catalog row is inactive or the account needs reconnect; disabling an unowned ID performs no mutation.

If a selected class later becomes inactive, its preference row is retained but produces no targets or future fan-out. Reconnecting may reactivate the same account-scoped catalog row after a complete class discovery; no class is substituted by matching only its name or journal value. Explicit account switching/replacement semantics are outside this phase.

## Routing and notification timing

The production target query requires an enabled subscription, active user, active catalog row, ownership-consistent account join, and `CONNECTED` status. It returns distinct account/catalog/journal targets without collapsing identical journals across accounts. Recipient lookup takes a catalog class ID and applies the same ownership, active-state, and connection filters, returning deterministic distinct internal user IDs.

The first successful reconciliation creates a no-spam baseline intent per current recipient. A subscription enabled after a baseline receives only future reconciliation events; enabling never synthesizes historical `NEW` events. Disabling stops future fan-out but leaves already-created durable recipient intents untouched.

## Telegram class selection

`/classes` renders active authorized catalog classes as an inline keyboard using human-readable class names. `✅` marks monitored classes and `☐` marks available ones. Pages contain at most eight classes with deterministic previous/next navigation. No journal, catalog, recipient, or lesson-period ID is shown in user-facing text.

Callback data is a compact versioned internal protocol: `c1:t:<catalogClassId>:<page>` for toggles and `c1:p:<page>` for navigation. It is strictly length-, shape-, action-, and numeric-range-validated. Only a private human callback with a message is accepted. The exact Telegram identity is registered, then the application service re-authorizes the catalog ID against that user before mutation. Group, supergroup, channel, bot, missing-message, cross-user, and inactive-class callbacks cannot mutate state. Valid callbacks are answered and the keyboard is refreshed from committed subscription state.

Raw `/subscribe <journalId>` and `/unsubscribe <journalId>` commands do not exist. `/subscriptions` displays class labels rather than journal IDs, and Telegram code does not load or decrypt VULCAN session material.

## V5 legacy handling

V5 renames the old subscription journal column to nullable `legacy_journal_id`. A legacy row is mapped only through its user’s one VULCAN account to exactly one catalog row with the same account-local journal. Safely mapped rows keep their enabled state and clear the legacy value. Unmappable rows are retained, disabled, and never become production targets. No ownership is guessed and V1–V4 migration files remain unchanged.
