# Schedule 429 investigation

## First invocation and harness localization

The first authorized diagnostic authenticated and verified the catalog (`classCount=26`),
then reported opaque `HARNESS_FAILURE`. Browser and Java schedule requests were both zero;
no retry occurred. Post-login Referer was `OTHER_ALLOWED`, with four cookies. The persisted
monitoring Referer was unavailable. No raw exception was retained.

Local Chromium reproduction exercised that original catalog-to-control path. Its schedule-menu
`getByText` used Java `UNICODE_CASE`, which Playwright rejects when converting the selector,
before navigation or schedule traffic. A regression test preserves this exact failing operation.
This is a proven harness defect consistent with the real failure; the discarded real exception
prevents claiming its exact historical failing operation with certainty.

The old path also assumed visible class/menu/today text, same-Page navigation, automatic schedule
loading, and a jQuery AJAX global. Those assumptions were never empirically validated. The revised
control removes them; it does not broaden selectors or modify production requests.

## Diagnostic design

The explicitly separate mode is:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-real-smoke.ps1 -InvestigateSchedule429
```

Only invoke with authorization for one browser login and at most one browser plus one Java
schedule request. Normal `-Run` semantics and the reviewed DPAPI CurrentUser -> binary stdin
secret boundary are unchanged. The script builds before decrypting, filters the child environment,
suppresses third-party output, and validates the entire finite report before forwarding it.
No diagnostic class enters the production jar; no Spring context, database, monitoring, Telegram,
subscription, or outbox operation is involved. CAPTCHA/MFA stop the invocation.

The retained authenticated Page must share the verified application's origin. One PlanLekcji
controller-page route is derived from the known production endpoint, validated by the existing
portal allowlist, and navigated once. No alternative routes are probed. A successful HTML response,
exact final route, and absence of visible login/security challenges are required. A missing or
redirected page fails closed. The actual provider page route remains subject to this real validation.

Request source is **BROWSER_CONTEXT_FETCH**. Native `fetch` executes in that initialized Page,
using a same-origin relative endpoint, automatic cookies, URL-encoded data and exactly the four
known fields. It does not assume or invoke site jQuery, insert headers/tokens, inject cookies, or
manipulate DOM state. This compares browser transport/context; it does **not** prove the headers
of the portal's native UI-generated request. Missing AJAX/security headers are diagnostic facts,
not evidence that those headers are unnecessary.

The schedule guard is unarmed during navigation and admits at most one eligible current-week
request afterward. Extra, mismatched, cross-origin and redirect requests are blocked. Service workers
are disabled. Existing local Chromium tests also attempt duplicate requests and self-redirects.
Only browser 2xx JSON with the expected successful envelope/arrays permits Java. Browser 429 stops
with case 1. Otherwise fresh production `VulcanSessionCapture` uses the same browser context;
one unmodified production `VulcanClient` call determines case 2, 3 or 4. No retry wrapper is used.
Production capture retains its actual last complete authenticated observation; entering the page
alone does not guarantee it changes the captured Referer.

Java header evidence is synthetic loopback calibration through the real production transport,
with Cookie/Referer structure projected from the indicated session material. It is not a real Java
wire capture. Persisted monitoring Referer stays unavailable: the harness opens no database or key.
Only booleans, counts and finite categories leave the driver. Response JSON is inspected ephemerally
for shape and its byte buffer cleared. No provider request/body dump, HAR, screenshot, trace,
storage-state file or provider fixture is written. Browser resources close on success or failure.

## Finite failure reporting

Only the first failing stage and category are retained, including callback and cleanup failures.
No exception message, selector, URL or provider text enters the output protocol.

Stages: `INVESTIGATION_INPUT`, `JAVA_TRANSPORT_CALIBRATION`, `AUTHENTICATED_BROWSER_READY`,
`CATALOG_READY`, `TARGET_SELECTION`, `PLAN_CONTEXT_DISCOVERY`, `PLAN_CONTEXT_NAVIGATION`,
`BROWSER_REQUEST_OBSERVER_SETUP`, `BROWSER_CONTROL_TRIGGER`, `BROWSER_CONTROL_WAIT`,
`POST_PLAN_SESSION_CAPTURE`, `JAVA_COMPARISON_SETUP`, `JAVA_COMPARISON`, `BROWSER_CLEANUP`.

Categories: `NOT_FOUND`, `AMBIGUOUS`, `NOT_ACTIONABLE`, `NAVIGATION_TIMEOUT`,
`REQUEST_NOT_OBSERVED`, `UNEXPECTED_PAGE_STATE`, `INTERNAL_INVARIANT`, `PLAYWRIGHT_TRANSIENT`.
Authentication additionally keeps the existing finite authentication outcome. A harness failure
has `category=HARNESS_FAILURE`, plus `stage` and `failureCategory`.

## Verification and second invocation

Normal verification is browser-free and makes zero real VULCAN calls. Optional Chromium fixtures
use only loopback and synthetic inputs:

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp '-Dtest=Schedule429LocalBrowserTest' '-Dschedule429.localBrowserTests=true' test
git diff --check
```

Verification passed: Maven reported 538 tests, zero failures/errors, three optional Chromium
methods skipped. All seven explicit local Chromium cases passed. Formatting and diff whitespace
checks passed; the production jar contains zero diagnostic classes. No real VULCAN request
occurred during this verification.

## Second authorized invocation (2026-09-05)

After committing and pushing harness `5bf745d31fc5d3222c9ddc44b3aa8fbe76c5de28`, exactly
one new real invocation ran. Authentication and catalog verification succeeded (`classCount=26`).
It stopped before the browser control:

```text
category=HARNESS_FAILURE
result=FAIL
stage=PLAN_CONTEXT_NAVIGATION
failureCategory=UNEXPECTED_PAGE_STATE
browserSource=NOT_REACHED
browserScheduleRequests=0
javaScheduleRequests=0
blockedExtraScheduleRequests=0
javaPermitted=false
decisionCase=NOT_REACHED
postLoginRefererContext=OTHER_ALLOWED
postLoginCookieCount=4
persistedMonitoringRefererContext=UNAVAILABLE
retries=0
```

The controller-page navigation did not satisfy the required page-state checks. This category
covers a missing navigation response, unsuccessful/non-HTML response, changed final route, or
visible login state; the finite report does not distinguish those conditions. No raw page,
exception, URL, headers or response body was retained to infer more. No further invocation or
production change was made. Across both diagnostic invocations, real schedule requests total zero.
Browser schedule status/429, post-plan capture, cookie changes and form comparison remain unavailable.
Neither a successful browser control nor any decision-matrix case was reached.

The only header facts are a **synthetic Java transport projection from post-login material**:
verification token, AppGuid, X-Requested-With, Origin, Referer, Content-Type, User-Agent and cookies
present; Accept, Accept-Language and Sec-Fetch metadata absent. Projected cookie count is four and
Referer context is `OTHER_ALLOWED`. These are not real Java schedule-wire or browser comparison facts.

The process exited and no diagnostic Java process remained. No retry, raw provider artifact,
secret persistence, database mutation or production request fix occurred. The real 429 cause is
still unresolved; no production compatibility change is indicated by this result.

 `result=SUCCESS` means the two-control comparison completed, including a
classified Java failure; it does not mean monitoring passed.
