# Transactional notification outbox

## Purpose and atomic boundary

The outbox closes the failure window between schedule tracking and future delivery. A successful reconciliation now persists active tracking state and durable notification intent in the same PostgreSQL transaction. If either write fails, both roll back. No notification intent is created after commit or through an asynchronous application event.

A failed or incomplete schedule fetch never enters successful reconciliation, so it changes neither tracking state nor the outbox.

## Event model and minimization

The protocol-independent event types are:

- `BASELINE_ESTABLISHED` with the active-change count;
- `CHANGE_NEW`;
- `CHANGE_UPDATED`; and
- `CHANGE_RESOLVED`.

The first successful snapshot always creates exactly one baseline event, even when there are zero active changes. Existing changes do not create `CHANGE_NEW` spam during baseline establishment. Once a scope is established, each deterministic tracker transition creates one corresponding change event in the same order.

Change events contain only the journal/week scope, lifecycle-specific event type, semantic change key, protocol-neutral change type, lesson date and period identifier, nullable group and subject identifiers, and occurrence timestamp. The table has explicit columns rather than a generic serialized or JSON payload. It does not store VULCAN row IDs, raw responses, raw or unknown annotations, full snapshots, teachers, replacement codes, HTML, cookies, tokens, credentials, exception messages, or provider-specific data.

## Durable delivery lifecycle

New events begin as `PENDING`, with `attemptCount` zero and `nextAttemptAt` equal to the reconciliation timestamp.

A claim transaction selects at most a bounded batch of due `PENDING` rows and expired `IN_FLIGHT` rows in ID order using PostgreSQL `FOR UPDATE SKIP LOCKED`. Each claimed row becomes `IN_FLIGHT`, increments its attempt count, receives a lease expiry and a fresh ownership token, and is returned as an immutable message. The default batch is 25 and the default lease is two minutes. External delivery happens only after that short transaction completes.

The ownership token prevents a delayed worker from acknowledging a newer reclaimed attempt. A successful acknowledgement changes the owned row to `DELIVERED`, records `deliveredAt`, and clears its lease and token. Delivered rows are retained; retention and cleanup policy are future work.

Retryable failure returns the owned row to `PENDING`, clears the claim, and advances `nextAttemptAt` using bounded exponential delays of 5, 15, 45 seconds and so on, capped at 15 minutes. A provider hint can delay a retry but can never schedule it earlier. Unexpected runtime gateway failures are treated as retryable without persisting exception text. The default maximum is five delivery attempts; a retryable failure on the final attempt becomes `DEAD`. A permanent failure becomes `DEAD` immediately. Dead rows are retained and cannot be claimed.

If a process stops after claiming but before acknowledgement, the row remains `IN_FLIGHT`. Once the lease expires, another dispatcher can reclaim it with a new token and incremented attempt count, so claims cannot remain stuck permanently.

## Delivery contract

`NotificationDeliveryGateway` accepts an immutable, protocol-independent `NotificationOutboxMessage`. This phase supplies no production implementation. `NotificationOutboxDispatcher.dispatchOnce()` claims a batch, delivers sequentially outside the database transaction, and acknowledges each outcome in its own short transaction. It returns only claimed, delivered, retried, and dead counts.

The contract is at least once, not exactly once. Notification intent is not lost between tracking commit and delivery, pending work survives process restart, and stale claims recover. However, if an external provider accepts a message and the process stops before the `DELIVERED` acknowledgement commits, the lease will eventually expire and the message may be delivered again. A later provider adapter may use provider-specific idempotency when available.

There is no Telegram gateway, Telegram formatter/router, subscription integration, production delivery trigger, or outbox `@Scheduled` job yet. Normal startup performs no notification delivery, and monitoring can safely accumulate pending events without a gateway.
