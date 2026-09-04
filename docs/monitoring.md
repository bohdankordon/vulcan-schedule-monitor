# Monitoring orchestration

## Scope selection and ordering

Each `MonitoringTarget` contains one opaque journal identifier. `MonitoringTargetProvider` is a port only; there is intentionally no production subscription table or provider in this phase, and tracking rows are not treated as subscriptions.

At the start of each cycle, targets are deduplicated and sorted by journal identifier. The planner uses the injected clock in `Europe/Warsaw`, independent of the machine timezone. It produces exactly two separate Monday-to-Sunday scopes per target: the Polish current week followed by the next week. The two weeks remain two requests because multi-week VULCAN ranges have not been established as supported.

Scopes run sequentially. No delay occurs before the first request or after the last. A configurable minimum delay, 500 milliseconds by default, separates independent scope requests to avoid bursts. Retry delays are separate from ordinary request pacing.

## Failure and retry policy

The resilient weekly source makes at most three total attempts. Transport and server failures use bounded exponential delays of one second and two seconds. Other client errors and protocol/schema failures do not retry. Authentication failures, redirects, and unexpected successful HTML responses do not retry and stop the remainder of the cycle because all targets share the same account/session source.

For `429`, the transport parses both delta-seconds and HTTP-date `Retry-After` values. Missing or malformed values use a conservative 30-second fallback. A delay of at most ten seconds may be honored inline while attempts remain. Longer delays are not slept inline: an in-memory source/account gate stores the latest `notBefore` instant and returns a deferred outcome. Calls before that instant perform no outbound HTTP work, and a rate-limit deferral stops the remaining cycle.

The gate is process-local. Restarting the process loses this transient state. Durable account-level rate limiting can be revisited when account and session persistence exist. A `503` retry hint is also honored when it exceeds the normal exponential delay. Interrupted retry or pacing waits restore the thread interrupt flag and abort cleanly. Tests inject recording delays and do not sleep.

Permanent or protocol failure for one scope is recorded and later scopes continue. Exhausted transient failures may also leave independent scopes runnable. Outcomes contain only the scope, category, safe counts, and an optional defer-until timestamp—never snapshots, annotations, response bodies, headers, or session data.

## Reconciliation safety

Only a complete successful `ScheduleSnapshot` whose journal and week match the requested `TrackingScope` can pass through `ScheduleRefreshCoordinator` to `ScheduleChangeTracker.reconcileSuccessfulSnapshot(...)`. Every failure and deferral exits before that call. Existing active state therefore remains unchanged and cannot generate a false `RESOLVED` transition. This is covered at both application-service and PostgreSQL/Testcontainers boundaries.

## Scheduler configuration

The Spring trigger uses fixed-delay semantics with a five-minute default cadence and a local atomic overlap guard. It is disabled by default:

```yaml
vulcan:
  monitoring:
    enabled: false
    poll-interval: PT5M
```

When enabled, the first execution waits one configured interval. Required `MonitoringTargetProvider` and `WeeklyScheduleSource` beans must also be supplied explicitly; missing adapters fail application-context creation rather than simulating active monitoring. The application does not read browser-session environment values or make VULCAN calls during normal default startup.

The current deployment assumption is one application instance. There is no distributed scheduler lock. Successful reconciliation now appends durable notification intent to the implemented transactional outbox, but this monitoring scheduler does not dispatch it. Playwright login/re-login, credential persistence, a real subscription provider, `RefreshSession` keepalive, Telegram delivery, outbox dispatch scheduling, and production deployment are intentionally deferred.
