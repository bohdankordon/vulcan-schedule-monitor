# Monitoring orchestration

## Account-aware scope selection

VULCAN protocol identifiers are local to a connected account. A `journalId`, `classId`, or lesson-period ID must never be treated as globally unique. Each production `MonitoringTarget` therefore carries three identities:

- `vulcanAccountId` selects the encrypted session and recovery context;
- `catalogClassId` is the durable internal class/subscription identity; and
- `journalId` is sent only to the VULCAN schedule endpoint.

The subscription-backed target query requires an enabled subscription, active application user, active catalog class, matching user/account ownership, and a `CONNECTED` account. It returns one distinct target per catalog row in account/catalog/journal order. Two accounts containing journal 77 remain two targets.

The planner uses the injected clock in `Europe/Warsaw` and emits exactly two Monday-to-Sunday scopes per target: current week, then next week. The account and catalog identities are preserved in each `TrackingScope`; `ScheduleSnapshot` remains free of application account, user, and Telegram data. The coordinator checks only that the returned protocol journal/week matches the request before reconciling with the original account-aware scope.

Scopes run sequentially with configurable inter-request pacing. There is no delay before the first eligible request or after the last.

## Persisted session source and recovery

The production `PersistedAccountWeeklyScheduleSource` loads the encrypted session selected by `TrackingScope.vulcanAccountId`, constructs an isolated `VulcanClient`, requests the scope's journal/week, validates the response, and persists `session.snapshotMaterial()` after every successful request. This captures ordinary `Set-Cookie` rotation. If encrypted-session loading or rotation persistence fails, the source throws and the fetched snapshot never reaches tracking.

Production composition is deliberately layered: the persisted source performs session load/fetch/rotation only; `ResilientWeeklyScheduleSource` handles ordinary weekly transport/server retries and account-scoped rate limits; the outer `RecoveringAccountWeeklyScheduleSource` owns the authentication-recovery budget. Consequently, ordinary retries cannot re-enter or reset recovery.

Authentication-required HTTP responses, session redirects, and unexpected HTML trigger at most one recovery attempt for one logical scope execution. Recovery is serialized per account and reuses the existing Playwright authenticator only when encrypted remembered credentials exist. A successful recovery authenticates, verifies `getCache`/`getTree`, persists the verified session, and retries the weekly operation through the ordinary resilience layer, including post-fetch cookie rotation. A second authentication failure is not recovered again and marks the account reconnect-required.

Without remembered credentials, or after invalid credentials, CAPTCHA, MFA, an unsupported flow, or a protocol authentication failure, the account becomes `RECONNECT_REQUIRED`. `/status`, `/classes`, and `/connect` expose the path back to a working connection; the scheduler does not send an unreliable direct reconnect alert. A transient recovery failure has its own sanitized outcome, leaves account state intact, and stops further work for that account during the current cycle so a later cycle can retry once.

## Failure and rate-limit isolation

The resilient source makes at most three total attempts for transport and server failures, using bounded exponential backoff. Permanent client and protocol failures do not retry. A VULCAN `Retry-After` value is honored; long delays are stored in a process-local gate keyed by VULCAN account.

Authentication, transient recovery, or long rate-limit failure blocks only the remaining scopes for that account in the current cycle. Healthy accounts continue. Calls made for a gated account before expiry perform zero VULCAN HTTP work, while another account—even one using the same journal ID—remains eligible. After the injected clock passes the gate, that account resumes without sleeps in tests. The gate is intentionally process-local and is lost on restart.

Permanent, protocol, and exhausted transient failures remain scope-isolated. An interrupted delay restores the interrupt flag and stops the cycle globally because shutdown/cancellation is process-level.

## Reconciliation safety

Only a complete successful snapshot matching the requested journal/week can reach `ScheduleChangeTracker`. Tracking locks and persistence lookup use `catalog_class_id + week_start`, not journal/week. Existing V1–V4 journal-only tracking scopes remain with a null catalog ID as historical state and are never selected by production monitoring.

Every fetch, decryption, recovery, rate-limit, or session-persistence failure exits before reconciliation. Existing active state is therefore unchanged and cannot create a false `RESOLVED`. A new account/catalog/week always establishes its own no-spam baseline, even if ambiguous legacy history has the same journal and week.

## Scheduler configuration

Monitoring is disabled by default:

```yaml
vulcan:
  connection:
    enabled: false
  monitoring:
    enabled: false
    poll-interval: PT5M
```

Enabling monitoring requires the secure VULCAN connection infrastructure. The application then wires the persisted account source automatically; an empty subscription database performs no network work. The fixed-delay scheduler has a local overlap guard. Multi-instance coordination, durable rate-limit state, periodic catalog refresh outside reconnect, and production deployment remain future work.
