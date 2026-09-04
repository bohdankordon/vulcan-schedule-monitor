# Transactional notification outbox

## Purpose and atomic boundary

The outbox closes the failure window between schedule tracking and future delivery. One row now represents exactly one delivery intent for one internal application-user recipient. A successful reconciliation persists active tracking state and every recipient-specific intent in the same PostgreSQL transaction. If any insert fails, tracking changes and the entire fan-out roll back. No notification intent is created after commit or through an asynchronous application event.

A failed or incomplete schedule fetch never enters successful reconciliation, so it changes neither tracking state nor the outbox.

## Event model and minimization

The protocol-independent event types are:

- `BASELINE_ESTABLISHED` with the active-change count;
- `CHANGE_NEW`;
- `CHANGE_UPDATED`; and
- `CHANGE_RESOLVED`.

The first successful snapshot creates one baseline event per currently active recipient, even when there are zero active changes. Existing changes do not create `CHANGE_NEW` spam during baseline establishment. With no recipients, the tracking baseline still establishes and no outbox row is created. Once a scope is established, each deterministic tracker transition creates one corresponding event per active recipient, preserving transition order for each recipient. For example, two recipients and three transitions produce six rows.

Change events contain only the internal `recipient_user_id`, journal/week scope, lifecycle-specific event type, semantic change key, protocol-neutral change type, lesson date and period identifier, nullable group and subject identifiers, and occurrence timestamp. `recipient_user_id` is an application identity, not a Telegram user or chat ID. Telegram protocol identifiers exist only in `telegram_identity` and are resolved later through `TelegramRecipientDirectory`. The outbox has explicit columns rather than a generic serialized or JSON payload. It does not store Telegram IDs, VULCAN row IDs, raw responses, raw or unknown annotations, full snapshots, teachers, replacement codes, HTML, cookies, tokens, credentials, exception messages, or provider-specific data.

Recipient resolution includes enabled subscriptions owned by active users and returns deterministic ascending internal IDs. A user subscribing after a scope baseline is established receives no historical baseline or synthesized schedule-change events; only future reconciliations fan out to that user. Disabling a subscription stops future fan-out but does not mutate or delete an already-created durable intent.

Flyway V3 preserves V2 history. Recipient-less V2 rows that were `PENDING` or `IN_FLIGHT` cannot be routed safely, so migration makes them `DEAD` with the sanitized `UNROUTABLE` category and clears claim state. Recipient-less terminal history may remain. A database check requires every new actionable `PENDING` or `IN_FLIGHT` row to reference an `app_user`.

## Durable delivery lifecycle

New events begin as `PENDING`, with `attemptCount` zero and `nextAttemptAt` equal to the reconciliation timestamp.

A claim transaction selects at most a bounded batch of due `PENDING` rows and expired `IN_FLIGHT` rows in ID order using PostgreSQL `FOR UPDATE SKIP LOCKED`. Each claimed row becomes `IN_FLIGHT`, increments its durable `attemptCount`, receives a lease expiry and a fresh ownership token, and is returned as an immutable message. The default batch is 25 and the default lease is two minutes. External delivery happens only after that short transaction completes.

The ownership token prevents a delayed worker from acknowledging a newer reclaimed attempt. A successful acknowledgement changes the owned row to `DELIVERED`, records `deliveredAt`, and clears its lease and token. Delivered rows are retained; retention and cleanup policy are future work.

Retryable failure returns the owned row to `PENDING`, clears the claim, and advances `nextAttemptAt` using bounded exponential delays of 5, 15, 45 seconds and so on, capped at 15 minutes. A provider hint can delay a retry but can never schedule it earlier. Unexpected runtime gateway failures are treated as retryable without persisting exception text. The default maximum is five delivery attempts: claimed attempts 1 through 5 are eligible for provider delivery, and a retryable failure on attempt 5 becomes `DEAD`. A permanent failure becomes `DEAD` immediately. Dead rows are retained and cannot be claimed.

If a process stops after claiming but before acknowledgement, the row remains `IN_FLIGHT`. Once the lease expires, another dispatcher can reclaim it with a new token and incremented attempt count, so claims cannot remain stuck permanently. An abandoned fifth claim is therefore reclaimed internally as attempt 6 for durable cleanup, but the dispatcher does not invoke the provider for that over-budget claim. It marks the currently owned row `DEAD` with the sanitized `EXHAUSTED` category instead. The provider is never deliberately called for attempt 6 when the maximum is five.

## Delivery contract

`NotificationDeliveryGateway` accepts an immutable, protocol-independent `NotificationOutboxMessage` containing the internal recipient user ID. The separate future Telegram gateway will resolve that ID to one Telegram recipient and send one message. This phase supplies no production gateway. `NotificationOutboxDispatcher.dispatchOnce()` claims a batch, delivers sequentially outside the database transaction, and acknowledges each outcome in its own short transaction. It returns only claimed, delivered, retried, and dead counts.

The contract is at least once, not exactly once. Notification intent is not lost between tracking commit and delivery, pending work survives process restart, and stale claims recover. However, if an external provider accepts a message and the process stops before the `DELIVERED` acknowledgement commits, the lease will eventually expire and the message may be delivered again. A later provider adapter may use provider-specific idempotency when available.

There is no TelegramBots runtime, Telegram message sending, formatter/router, production delivery trigger, or outbox `@Scheduled` job yet. Normal startup performs no notification delivery, and monitoring can safely accumulate pending recipient intents without a gateway. The next phase can add Telegram commands and delivery without changing the outbox ownership model.
