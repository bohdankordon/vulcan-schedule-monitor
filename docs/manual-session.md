# Manual Session Setup

## Scope

Phase 1 accepts an already authenticated VULCAN browser session as temporary development plumbing. It does not perform login, bypass authentication, refresh an expired session, or run a live probe automatically. Use it only with an account and resources you are authorized to access; never circumvent access restrictions.

The repository provides a programmatic entry point rather than an application-startup probe so normal startup, tests, and CI can never make an accidental external request.

## Session inputs

A local integration harness can read these values from its environment and pass them to `VulcanSession.fromBrowserSession(...)`:

- `VULCAN_APPLICATION_BASE_URI` — the authenticated application root URI;
- `VULCAN_REQUEST_VERIFICATION_TOKEN` — the request verification header value;
- `VULCAN_APP_GUID` — the application identifier header value;
- `VULCAN_COOKIE_HEADER` — the browser request's cookie pairs; and
- `VULCAN_REFERER` — the relevant authorized application page URI.

These names are documentation conventions; the application does not automatically load them. Use placeholders in local scripts and IDE settings. Never put values in source files, command examples, test fixtures, logs, issue reports, or commits.

## Programmatic flow

Construct a `VulcanSession`, then a `VulcanClient`. The intended sequence is:

1. call `getCache()` to obtain the current school year and lesson-period mapping;
2. pass the year to `getTree(...)` and select an authorized journal from the result; and
3. pass that discovered journal identifier and a date within a week to `getWeekSchedule(...)`.

Only print safe counts or non-personal summaries during manual verification. Do not print the session object, cookies, headers, response bodies, staff data, or raw schedule payloads. Log out or otherwise revoke the browser session after testing where appropriate, and remove the values from the local environment.

Automated Playwright authentication and session recovery are planned for a later phase.
