# Users and monitoring subscriptions

## Internal users and Telegram identity

`app_user` is the service's protocol-neutral identity. It contains only an identity key, active flag, and creation/update timestamps. Notification delivery state and monitoring subscriptions reference this internal ID.

`telegram_identity` is a separate one-to-one mapping from an application user to the minimum routing data required by the planned private-chat bot: a 64-bit Telegram user ID and a 64-bit private chat ID. Both are unique. Usernames, first and last names, profile photos, locale, and message content are not persisted.

Telegram registration is atomic. An unknown Telegram user creates one application user and identity. A known Telegram user reuses the same application user and updates a changed private chat ID. Registering a known inactive user explicitly reactivates that user rather than silently creating a duplicate. Persisted timestamps come from the application `Clock`.

The immutable `TelegramRecipientReference` returned by `TelegramRecipientDirectory` keeps JPA entities behind the persistence boundary. The Telegram delivery gateway uses it to translate the internal outbox recipient into the stored private chat only at the adapter edge.

## Subscription lifecycle

`monitoring_subscription` is unique by internal application user and journal. Enabling the same pair repeatedly is idempotent. Disabling changes `enabled` to false, and re-enabling reuses the existing row rather than creating history duplicates. Application services expose enable, disable, active-journal listing, and subscription checks so a future Telegram adapter never needs direct repository access.

The production target provider returns each journal once when it has at least one enabled subscription owned by an active user. Results are ordered by journal ID. The notification recipient provider independently returns distinct active internal application-user IDs for one journal in ascending order; it does not expose Telegram user or chat IDs.

## Notification timing semantics

The first successful reconciliation for a scope creates `BASELINE_ESTABLISHED` once per active recipient. A scope can still establish its tracking baseline with zero recipients, producing no notification intent. Later `NEW`, `UPDATED`, and `RESOLVED` transitions each fan out once per recipient in the reconciliation transaction.

A subscription added after baseline establishment receives only future reconciliation events. It receives no historical baseline and no synthesized past change. The future interactive Telegram command can acknowledge “tracking enabled” directly.

Disabling a subscription affects future fan-out only. Existing recipient-specific outbox rows remain durable because they represented delivery intents created while the subscription was enabled. Explicit pending-notification cancellation, if desired, is a separate future product feature.

## Telegram interaction

Supported private-chat commands register or refresh the exact sender-user/private-chat pair before invoking an application-facing handler. `/status` and `/subscriptions` read active journal IDs through `MonitoringSubscriptionService`. Until a persisted class catalog exists, responses deliberately call these opaque IDs “schedule references,” not class names.

Raw `/subscribe <journalId>` and `/unsubscribe <journalId>` commands do not exist. Future subscription changes will use classes dynamically discovered from the user's authorized VULCAN account. VULCAN login/session recovery, Playwright, secure web connection, class-selection keyboards, human-readable class labels, and deployment remain planned.
