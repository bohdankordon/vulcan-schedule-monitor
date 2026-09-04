# Telegram adapter

## Integration choice and configuration

The adapter uses the official TelegramBots 10.2.1 BOM with only `telegrambots-longpolling` and `telegrambots-client`. It integrates core modules manually with Spring Boot 4.1.1. The TelegramBots Spring Boot starter is intentionally absent because its dependency-management boundary targets Spring Boot 3.x.

Telegram is disabled by default:

```yaml
telegram:
  bot:
    enabled: false
    token: ${TELEGRAM_BOT_TOKEN:}
  dispatch:
    interval: PT2S
    initial-delay: PT2S
```

Disabled startup creates no Telegram network-facing beans and requires no token. Enabling the adapter with a blank token fails with a sanitized configuration error. The token-bearing properties object is a normal class with a redacted `toString()`; tokens, client URLs, bot sessions, raw updates, provider response descriptions, and recipient identifiers are never logged. Set the token only through the `TELEGRAM_BOT_TOKEN` environment variable in normal operation.

## Long polling and lifecycle

The inbound flow is:

```text
Telegram Bot API -> long polling -> TelegramUpdateConsumer -> TelegramUpdateRouter
                 -> command handler/callback router -> application service
```

`TelegramBotsLongPollingApplication` is wrapped behind a small engine/factory boundary. Before registration, the engine calls the side-effect-free Bot API `getMe` method through the same engine-owned OkHttp client and Telegram URL. This structured preflight is necessary because TelegramBots 10.2.1 can report its internal registration-time `deleteWebhook` failure as `TelegramApiErrorResponseException`, whose HTTP code is not exposed. The adapter does not parse exception strings or use reflection. A structured preflight `401` suspends Telegram until restart, while `429` honors `retry_after` (with the existing 30-second fallback). If registration still fails after a successful preflight without structured details, it is conservatively transient.

Each registration attempt owns a named scheduled executor and an OkHttp client. A failed partial attempt closes the wrapper; shutdown closes bot sessions/application first, then shuts down the executor, OkHttp dispatcher, connection pool, and optional cache.

TelegramBots uses a 50-second server-side `getUpdates` long poll. The owned long-polling HTTP client therefore has a deliberately larger, finite 65-second read timeout, preventing an idle connection from being mistaken for a transport failure while retaining a bounded network wait.

Registration runs under a thin scheduled supervisor rather than application-context startup. Transient failures keep PostgreSQL, monitoring, and outbox accumulation alive and retry without sleeping after 5 seconds, 15 seconds, 45 seconds, then two minutes capped. Authentication failure suspends Telegram work until process restart. Restart clears all process-local gate and retry state.

## Accepted updates and commands

Only private-chat messages and inline callbacks from human senders are accepted. Groups, supergroups, channels, bot senders, missing sender/chat fields, edited/media messages, inline queries, unknown commands, and non-command text are ignored without persistence. Batch order is preserved and a failure in one update does not abort later updates.

For every supported command, the adapter registers the exact Telegram sender ID and private chat ID through `TelegramIdentityRegistration`; it never derives one from the other. It does not persist usernames, names, locale, or message text.

Supported commands:

- `/start` — welcome and current limitations;
- `/help` — supported command list;
- `/status` — safe VULCAN connection state, available-class count, and active-monitoring count;
- `/subscriptions` — selected classes by human-readable label;
- `/classes` — an authorized, paginated inline class-selection keyboard; and
- `/connect` — a new short-lived, single-use HTTPS connection link when the feature is enabled, or a safe disabled message otherwise.

Bot-name suffixes such as `/start@somebot`, surrounding whitespace, and case normalization are supported. There are no raw journal-ID subscription mutation commands. `/connect` never parses credential arguments and never logs the generated URL. Credentials are entered only on the self-contained Spring MVC page. Users must never send VULCAN credentials through Telegram.

`/classes` lists at most eight active catalog classes per page. `✅` and `☐` show committed selection state, while previous/next controls preserve deterministic catalog ordering. The visible text contains class labels, never journal, catalog, recipient, or lesson-period IDs. No-connection, reconnect-required, and empty-catalog states give explicit guidance.

Callback data is versioned as `c1:t:<catalogClassId>:<page>` or `c1:p:<page>`, capped at Telegram's 64-byte boundary, and strictly parsed. A callback can mutate only after the exact private Telegram identity is registered and `MonitoringSubscriptionService` verifies that the catalog row is active, connected, and owned by that application user. Cross-user or stale catalog IDs are rejected; group/channel/bot callbacks cannot mutate. Valid callbacks are answered and the original keyboard is edited from the committed state. Raw callback payload and internal identifiers are not logged.

Command replies are direct best-effort plain-text sends and are not durable. A reply failure is sanitized and isolated from long polling.

## Durable notification delivery

Monitoring delivery follows:

```text
notification_outbox -> NotificationOutboxDispatcher
                    -> TelegramNotificationDeliveryGateway
                    -> TelegramRecipientDirectory
                    -> plain-text formatter -> Telegram Bot API
```

The pure formatter covers baseline, new, updated, and resolved events plus teacher-substitution and unknown change types. The gateway authorizes the outbox catalog class against its internal recipient, resolves its human-readable class label, and displays that label with week/date, lifecycle, change type, or active-change count. It deliberately omits journal/catalog/recipient IDs, Telegram IDs, change keys, subject/group/teacher IDs, replacement codes, raw annotations, and the opaque lesson-period ID. Rich subject, named period, and teacher details remain future work.

Structured Telegram API failures are classified as rate-limited, authentication, permanent, or transient. `429` uses structured `ResponseParameters.retryAfter` (30 seconds fallback), extends a process-local not-before gate, and returns a matching retry delay to the outbox. `401` suspends the provider until restart while leaving the current intent retryable. Other 4xx failures, including 400 and 403, are permanent; 5xx and generic transport failures are retryable.

The scheduler uses a conservative batch size of one, a two-second default cadence, a local overlap guard, and one dispatch call per tick. It checks the provider gate before any claim, so deferred or suspended ticks consume no attempts and send nothing. Existing two-minute leases, five attempts, and the 15-minute retry cap remain unchanged. Delivery is at least once: provider acceptance followed by a crash before acknowledgement may produce a duplicate.

This adapter is not full product readiness. Explicit account switching/disconnect, a durable reconnect-required notification event, richer rendering, periodic catalog refresh outside reconnect, deployment, multi-instance coordination, and delivered-outbox retention cleanup remain planned.
