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
                 -> command handler -> existing application service
```

`TelegramBotsLongPollingApplication` is wrapped behind a small engine/factory boundary. Before registration, the engine calls the side-effect-free Bot API `getMe` method through the same engine-owned OkHttp client and Telegram URL. This structured preflight is necessary because TelegramBots 10.2.1 can report its internal registration-time `deleteWebhook` failure as `TelegramApiErrorResponseException`, whose HTTP code is not exposed. The adapter does not parse exception strings or use reflection. A structured preflight `401` suspends Telegram until restart, while `429` honors `retry_after` (with the existing 30-second fallback). If registration still fails after a successful preflight without structured details, it is conservatively transient.

Each registration attempt owns a named scheduled executor and an OkHttp client. A failed partial attempt closes the wrapper; shutdown closes bot sessions/application first, then shuts down the executor, OkHttp dispatcher, connection pool, and optional cache.

Registration runs under a thin scheduled supervisor rather than application-context startup. Transient failures keep PostgreSQL, monitoring, and outbox accumulation alive and retry without sleeping after 5 seconds, 15 seconds, 45 seconds, then two minutes capped. Authentication failure suspends Telegram work until process restart. Restart clears all process-local gate and retry state.

## Accepted updates and commands

Only ordinary private-chat messages from human senders containing recognized text commands are accepted. Groups, supergroups, channels, edited messages, callback and inline queries, bot senders, missing sender/chat fields, media-only messages, unknown commands, and non-command text are ignored without persistence. Batch order is preserved and a failure in one update does not abort later updates.

For every supported command, the adapter registers the exact Telegram sender ID and private chat ID through `TelegramIdentityRegistration`; it never derives one from the other. It does not persist usernames, names, locale, or message text.

Supported commands:

- `/start` — welcome and current limitations;
- `/help` — supported command list;
- `/status` — Telegram registration and active-monitoring count;
- `/subscriptions` — temporary schedule references; and
- `/connect` — information about the future secure HTTPS connection flow.

Bot-name suffixes such as `/start@somebot`, surrounding whitespace, and case normalization are supported. There are no raw journal-ID subscription mutation commands. Secure VULCAN account connection, class discovery, class-selection keyboards, and human-readable class labels remain planned. Users must never send VULCAN credentials through Telegram.

Command replies are direct best-effort plain-text sends and are not durable. A reply failure is sanitized and isolated from long polling.

## Durable notification delivery

Monitoring delivery follows:

```text
notification_outbox -> NotificationOutboxDispatcher
                    -> TelegramNotificationDeliveryGateway
                    -> TelegramRecipientDirectory
                    -> plain-text formatter -> Telegram Bot API
```

The pure formatter covers baseline, new, updated, and resolved events plus teacher-substitution and unknown change types. Current minimized outbox data permits a labelled journal-based “schedule reference,” week/date, lifecycle, change type, and active-change count. It deliberately omits internal recipient IDs, Telegram IDs, change keys, subject/group/teacher IDs, replacement codes, raw annotations, and the opaque lesson-period ID. Rich subject, period, teacher, and class labels wait for the VULCAN account/class-catalog phase.

Structured Telegram API failures are classified as rate-limited, authentication, permanent, or transient. `429` uses structured `ResponseParameters.retryAfter` (30 seconds fallback), extends a process-local not-before gate, and returns a matching retry delay to the outbox. `401` suspends the provider until restart while leaving the current intent retryable. Other 4xx failures, including 400 and 403, are permanent; 5xx and generic transport failures are retryable.

The scheduler uses a conservative batch size of one, a two-second default cadence, a local overlap guard, and one dispatch call per tick. It checks the provider gate before any claim, so deferred or suspended ticks consume no attempts and send nothing. Existing two-minute leases, five attempts, and the 15-minute retry cap remain unchanged. Delivery is at least once: provider acceptance followed by a crash before acknowledgement may produce a duplicate.

This adapter is not full product readiness. Secure VULCAN connect pages, Playwright login, encrypted session or credential persistence, automatic re-login, user/account association, a persisted class catalog, class-selection keyboards, subscription changes through discovered classes, richer rendering, deployment, multi-instance coordination, and delivered-outbox retention cleanup remain planned.
