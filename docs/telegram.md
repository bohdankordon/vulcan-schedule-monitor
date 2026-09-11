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

## Accepted updates, commands, and language preferences

Only private-chat messages and inline callbacks from human senders are accepted. Groups, supergroups, channels, bot senders, missing sender/chat fields, edited/media messages, inline queries, unknown commands, and non-command text are ignored without persistence. Batch order is preserved and a failure in one update does not abort later updates.

For every supported command or callback, the adapter registers or updates the exact Telegram sender ID and private chat ID through `TelegramIdentityRegistration`; it never derives one from the other. It does not persist usernames, names, raw Telegram client locale, or message text.

The bot supports four languages:
- English (`en`, default)
- Russian (`ru`)
- Ukrainian (`uk`)
- Polish (`pl`)

Language preference is persisted authoritatively in `telegram_identity.language_code VARCHAR(2) NOT NULL DEFAULT 'en'`, constrained by `CHECK (language_code IN ('en', 'ru', 'uk', 'pl'))` introduced in Flyway migration `V6__add_telegram_language.sql`. Existing users default to `en`.

Supported commands:

- `/start` — presents an interactive language selector (2x2 inline keyboard) for first-time or returning users. Upon selection, the message is edited in-place into the localized welcome text, the language preference is saved, and the native command menu is synchronized for that chat.
- `/language` — displays a language selection keyboard where the currently active language is marked with `✅`. Choosing a language updates the preference, reconfigures the native menu, and confirms the change.
- `/help` — localized command overview and security reminders.
- `/status` — localized safe VULCAN connection state, available-class count, and active-monitoring count.
- `/subscriptions` — localized view of selected classes by human-readable label.
- `/classes` — an authorized, paginated inline class-selection keyboard localized into the user's preferred language.
- `/connect` — a new short-lived, single-use HTTPS connection link when the feature is enabled, or a safe localized disabled message otherwise.

Bot-name suffixes such as `/start@somebot`, surrounding whitespace, and case normalization are supported. There are no raw journal-ID subscription mutation commands. `/connect` never parses credential arguments and never logs the generated URL. Credentials are entered only on the self-contained Spring MVC page. Users must never send VULCAN credentials through Telegram.

## Native Telegram command menu

On bot startup during long polling initialization, the adapter registers default English command descriptions for all private chats (`BotCommandScopeAllPrivateChats`) via `SetMyCommands`:
- `connect` — securely connect VULCAN
- `classes` — choose classes to monitor
- `subscriptions` — view monitored classes
- `status` — check status
- `language` — change language
- `help` — show all commands

When a user selects or updates their language preference via `/start` or `/language`, the adapter issues chat-scoped commands (`BotCommandScopeChat(chatId)`) in the selected language (`en`, `ru`, `uk`, or `pl`) and ensures the chat menu button is configured via `SetChatMenuButton` (`MenuButtonCommands`).

Menu configuration failure is treated strictly as a non-fatal presentation issue: transport errors are logged as warnings and never roll back the persisted language preference or crash the bot.

## Interactive keyboards and callback routing

Callbacks are partitioned into distinct, strictly validated versioned namespaces, both well within Telegram's 64-byte payload limit:
- Class selection namespace (`c1`):
  - `c1:t:<catalogClassId>:<page>` — toggle monitoring subscription for a class.
  - `c1:p:<page>` — navigate between pages of classes.
- Language selection namespace (`l1`):
  - `l1:s:<lang>` — language selection originating from the `/start` chooser.
  - `l1:c:<lang>` — language selection originating from the `/language` command.

`/classes` lists at most eight active catalog classes per page. `✅` marks monitored classes and `⬜` marks available ones (the obsolete ballot box glyph is not used). Navigation controls (`⬅️ Previous` / `Next ➡️`) and callback acknowledgments are fully localized into the user's language.

A callback can mutate state only after the exact private Telegram identity is registered and `MonitoringSubscriptionService` verifies that the catalog row is active, connected, and owned by that application user. Cross-user, stale catalog IDs, or malformed callbacks are rejected and answered with safe, localized feedback without logging raw payloads or internal IDs. Group, supergroup, channel, or bot callbacks cannot mutate state.

Command replies are direct best-effort plain-text sends and are not durable. A reply failure is sanitized and isolated from long polling.

## Durable notification delivery and delivery-time localization

Monitoring delivery follows:

```text
notification_outbox -> NotificationOutboxDispatcher
                    -> TelegramNotificationDeliveryGateway
                    -> TelegramRecipientDirectory
                    -> plain-text formatter -> Telegram Bot API
```

Notification messages in `notification_outbox` store pure domain events without pre-rendered localized copy. Localization is resolved at **delivery time**:
1. When dispatching an outbox message, `TelegramNotificationDeliveryGateway` queries `TelegramRecipientDirectory` for the recipient's current private chat ID and persisted `TelegramLanguage`.
2. `TelegramNotificationFormatter` formats the message using `TelegramTextCatalog` for the recipient's language.
3. If a user changes their language preference between notification creation and dispatch, the delivered notification is rendered in the newly chosen language.

Dates are consistently formatted as `dd.MM.yyyy` (e.g., `31.08.2026 — 06.09.2026` for week intervals or `02.09.2026` for change lesson dates).

The pure formatter covers baseline, new, updated, and resolved events plus teacher-substitution and unknown change types across all four supported languages. The gateway authorizes the outbox catalog class against its internal recipient, resolves its human-readable class label, and displays that label with week/date, lifecycle, change type, or active-change count. It deliberately omits journal/catalog/recipient IDs, Telegram IDs, change keys, subject/group/teacher IDs, replacement codes, raw annotations, and the opaque lesson-period ID.

Structured Telegram API failures are classified as rate-limited, authentication, permanent, or transient. `429` uses structured `ResponseParameters.retryAfter` (30 seconds fallback), extends a process-local not-before gate, and returns a matching retry delay to the outbox. `401` suspends the provider until restart while leaving the current intent retryable. Other 4xx failures, including 400 and 403, are permanent; 5xx and generic transport failures are retryable.

The scheduler uses a conservative batch size of one, a two-second default cadence, a local overlap guard, and one dispatch call per tick. It checks the provider gate before any claim, so deferred or suspended ticks consume no attempts and send nothing. Existing two-minute leases, five attempts, and the 15-minute retry cap remain unchanged. Delivery is at least once: provider acceptance followed by a crash before acknowledgement may produce a duplicate.

This adapter is not full product readiness. Explicit account switching/disconnect, a durable reconnect-required notification event, richer rendering, periodic catalog refresh outside reconnect, deployment, multi-instance coordination, and delivered-outbox retention cleanup remain planned.
