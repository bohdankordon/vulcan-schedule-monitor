# Manual Session Setup

## Scope

The low-level client still accepts an already authenticated VULCAN browser session for authorized protocol development. Phase 7 also provides an opt-in Playwright direct-login path through the secure connection page. Neither path bypasses authentication, MFA, CAPTCHA, SSO, or other access controls, and neither runs a live probe during default startup or tests.

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

For an authorized manual smoke test of the Phase 7 adapter, install Chromium explicitly on the runtime host:

```powershell
.\mvnw.cmd -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium" exec:java
```

Then enable the connection feature with a localhost or HTTPS public base URL and an environment-only 32-byte Base64 master key. Supply credentials through the CSRF-protected web form, not command-line arguments, source files, Telegram, screenshots, traces, or storage-state files. The adapter runs a fresh headless context, does not write browser state, and closes all resources on every outcome. Real portal behavior remains an authorized manual smoke-test responsibility because CI uses a fake authenticator and never downloads Chromium.
