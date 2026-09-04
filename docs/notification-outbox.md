# Transactional notification outbox

## Purpose and atomic boundary

One outbox row represents one delivery intent for one internal application-user recipient and one durable catalog class. Successful tracking state and every recipient-specific intent commit in the same PostgreSQL transaction. If any insert fails, tracking and the entire fan-out roll back. External Telegram delivery occurs only after the short claim transaction.

A failed or incomplete schedule fetch, failed session decryption, unsuccessful recovery, or failed cookie-rotation persistence never enters reconciliation and therefore changes neither tracking state nor outbox.

## Event identity and minimization

Event types remain `BASELINE_ESTABLISHED`, `CHANGE_NEW`, `CHANGE_UPDATED`, and `CHANGE_RESOLVED`. A baseline contains only the active-change count. Change events contain minimized semantic key/type/date/opaque period metadata needed by the tracker and formatter.

Actionable rows carry both `recipient_user_id` and `catalog_class_id`. The catalog ID is the schedule identity used for account-safe routing and label resolution; `journal_id` remains only protocol/audit context and is never used as global recipient identity. The outbox contains no Telegram IDs, VULCAN session data, portal URI, credentials, cookies, raw responses, full snapshots, raw annotations, student or teacher data, HTML, exception messages, or generic JSON payload.

Recipient lookup is by catalog class and requires an enabled subscription, active user, active catalog row, ownership-consistent account, and `CONNECTED` status. Late subscription creates no historical event. Unsubscribe or later catalog deactivation does not delete an existing durable intent: delivery resolves the catalog row through the original recipient even if the row is now inactive, preserving the human-readable class label.

## V5 legacy handling

V5 backfills a pre-V5 row's `catalog_class_id` only when its internal recipient maps through that user's one VULCAN account to exactly one catalog row matching the legacy journal. Remaining `PENDING` or `IN_FLIGHT` rows are ambiguous and become `DEAD/UNROUTABLE`; lease and claim state are cleared. Historical `DELIVERED` and `DEAD` rows may remain catalog-less and are never claimable. A database check requires both recipient and catalog identities on every new actionable row.

V1–V4 migrations are immutable. V5 also preserves ambiguous legacy tracking history without trying to reuse it for new notification production.

## Durable delivery lifecycle

New events start `PENDING` with attempt zero. A bounded claim selects due pending or expired in-flight rows in ID order with PostgreSQL `FOR UPDATE SKIP LOCKED`, moves them to `IN_FLIGHT`, increments the durable attempt count, and assigns a lease plus ownership token.

The token prevents a stale worker acknowledging a newer claim. Success produces `DELIVERED`. Retryable failure returns the owned row to `PENDING` with bounded exponential delay, honoring a later provider retry hint. Permanent failure and exhausted attempts become `DEAD`. Expired claims can be reclaimed, so crashes do not leave work permanently stuck. Delivery is at least once: provider acceptance followed by a crash before acknowledgement can result in a duplicate.

The generic defaults are batch 25, a two-minute lease, five attempts, and a 15-minute retry cap. Telegram deliberately dispatches batch size one and checks its provider gate before claiming.

## Telegram delivery contract

`NotificationOutboxMessage` exposes the internal recipient and catalog identities to the delivery adapter, not to the user. `TelegramNotificationDeliveryGateway` resolves the private chat through `TelegramRecipientDirectory` and the class label through `VulcanClassCatalog`, then formats safe text such as `Class: Synthetic 2A`. It does not display journal, catalog, recipient, change-key, group, subject, or opaque lesson-period identifiers.

Monitoring notifications use this durable path. Direct `/classes`, `/connect`, and other command acknowledgements are best effort and intentionally do not pretend to be durable schedule events. A reconnect-required alert is not sent directly from the scheduler; a future durable account-level notification would require its own explicit event model.

Delivered-row retention cleanup remains future work.
