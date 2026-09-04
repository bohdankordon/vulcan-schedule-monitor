# Account-aware monitoring

## Identity model

VULCAN's `journalId`, `classId`, and lesson-period identifiers are local to a provider account. Two connected accounts may both contain journal 77 without referring to the same class. The application uses `vulcan_class_catalog.id` as its durable class-selection identity because each catalog row is already unique within a VULCAN account.

The monitoring path preserves three separate responsibilities:

```text
app user -> one VULCAN account -> catalog class -> account-local journal
                                   |                 |
                                   |                 +-> VULCAN request only
                                   +-> subscription, tracking, outbox, callback identity
```

`MonitoringTarget` and `TrackingScope` carry account, catalog, and journal IDs. Account chooses the encrypted session/recovery/rate-limit context, catalog scopes persistence and recipient routing, and journal remains protocol context. `ScheduleSnapshot` contains only schedule data plus journal/week, never application or Telegram identity.

## Flyway V5

Released V1–V4 migrations remain unchanged. V5 makes these additive/forward changes:

- `monitoring_subscription.journal_id` becomes nullable `legacy_journal_id` and `catalog_class_id` becomes the active FK;
- enabled subscriptions require a catalog row and are unique by user/catalog;
- `tracking_scope.catalog_class_id` is nullable only so ambiguous history can remain; new scopes are unique by non-null catalog/week;
- the old global journal/week tracking uniqueness is removed, with a partial rule retained for legacy rows;
- `notification_outbox.catalog_class_id` becomes required together with its internal recipient for actionable rows.

A legacy subscription is backfilled only when its user’s one account has exactly one catalog row matching the legacy journal. Otherwise it is retained disabled. Legacy tracking scopes cannot be assigned safely and remain catalog-less historical state; production repositories never select or lock them. Legacy actionable outbox rows are backfilled only through an unambiguous recipient/account/catalog match. Remaining pending or in-flight rows become `DEAD/UNROUTABLE` with cleared claims, while terminal history may remain catalog-less.

This policy avoids guessed ownership, cross-account changes, false `UPDATED`/`RESOLVED` transitions, and delivery of ambiguous notifications.

## Runtime path

An enabled preference produces a target only when its user and catalog row are active, its account is `CONNECTED`, and all ownership joins agree. The planner emits current and next week for every distinct catalog class. Tracking locks `catalog_class_id + week_start`, so same-journal accounts establish independent baselines and active state.

The persisted weekly source decrypts only the selected account's session, builds a per-call `VulcanClient`, fetches the account-local journal, and encrypts post-response cookie rotation before returning a snapshot. If any of those steps fails, reconciliation is not invoked.

Authentication-required responses trigger one recovery attempt. Remembered credentials allow the existing isolated Playwright boundary to re-authenticate and verify a new session before one weekly retry. No remembered credentials or non-transient interactive authentication failures produce `RECONNECT_REQUIRED`; transient recovery leaves the account eligible for a later attempt. No CAPTCHA or MFA bypass exists.

The cycle blocks remaining work only for the account that encountered unrecoverable authentication or a long rate limit. Another account continues even when its journal number is identical. Rate gates and recovery locks are account keyed and process local.

## Telegram boundary

Telegram reads only safe connection state, catalog metadata, and subscription views. It never loads or decrypts a VULCAN session. `/classes` renders labels and selection markers; callback data carries catalog IDs only as an internal protocol and every toggle is re-authorized against the exact private-chat user. Notifications resolve their catalog label at delivery and expose no internal routing or provider identifiers.

There are no raw journal subscription commands and no scheduler-to-Telegram reconnect shortcut. A future reconnect alert needs an explicit durable account-level event model.

## Remaining limitations

Authorized manual validation against real tenant login variants is still required. Explicit account switching/disconnect, a durable reconnect-required notification event, richer named lesson/subject/teacher rendering, periodic catalog refresh outside reconnect, durable multi-instance coordination/rate limits, outbox retention, observability, and production deployment remain planned or optional.
