# Schedule transport investigation

## Earlier controls

Two earlier invocations authenticated and verified the catalog (`classCount=26`), but both
stopped before schedule traffic. The first opaque failure was followed by a local reproduction
of Playwright rejecting the harness selector's Java `UNICODE_CASE` flag. Because the original
exception was discarded, its exact historical failing operation remains unproven.

The second reported `PLAN_CONTEXT_NAVIGATION / UNEXPECTED_PAGE_STATE`, also with zero browser
and Java schedule requests. Direct PlanLekcji controller-page navigation was an **unverified
harness prerequisite**. It has been abandoned, with no replacement menu, link or selector guess.
Neither earlier invocation established any cause for the independent monitoring HTTP 429s.

## Current experiment: the post-login starting state

The question is whether Chromium and unchanged Java transport can call the schedule endpoint
from the same captured post-login state. There is no Plan page navigation or post-plan capture.
This experiment does not test whether visiting the actual PlanLekcji UI is required.

1. Authenticate through the reviewed Playwright flow and retain the current Page/BrowserContext.
   Capture immutable `postLogin` material before verification or schedule traffic.
2. Run production `DefaultVulcanSessionVerifier.verifyAndDiscover(postLogin)` in its own reconstructed
   Java session to obtain the authorized catalog. Select one journal internally. Use returned
   session material only for count/category/boolean drift comparison with `postLogin`.
3. From the existing allowed authenticated Page, issue one same-origin browser `fetch` to the exact
   production endpoint. Resolve its relative path from the captured application base, independently
   of the current page route. Use POST, `credentials=same-origin`, `redirect=manual`, URLSearchParams
   and exactly the four known fields for one journal/current week.
4. Supply only `X-V-RequestVerificationToken`, `X-V-AppGuid` from `postLogin`, and the fixed
   `X-Requested-With: XMLHttpRequest`. Pass ephemeral Playwright arguments without application
   serialization/logging/storage. Chromium supplies cookies, Origin, Referer, User-Agent, language
   and fetch metadata naturally. This is `BROWSER_CONTEXT_FETCH`, not native portal UI automation.
5. Only browser 2xx JSON with the expected successful schedule envelope/arrays permits one Java
   call. Reconstruct `VulcanSession.fromMaterial(postLogin)` using the **pre-request** material,
   never verifier output or post-browser material. Use the real production `VulcanClient`, adapter
   and transport without a resilience wrapper or header changes.

The browser guard permits at most one exact POST, with exactly four form keys, catalog-authorized
journal and current-week boundaries. Extra requests are aborted. Unsafe control requests stop
with a finite security failure. Redirect interception prevents automatic extra schedule requests;
service workers are disabled. CAPTCHA/MFA/invalid credentials stop the invocation. No retries.

## Output and interpretation

The output protocol accepts only finite enums, booleans and counts. It reports post-login and
verified cookie counts, cookie-material/count changes, Referer categories and category changes.
Cookie-material comparison ignores ordering of identical pairs; it never emits names or values.
Actual browser header presence and whether its Referer matches the captured Referer are observed.
Java header evidence remains **synthetic production-transport calibration**, with Cookie/Referer
structure projected from pre-request material; it is not a real Java wire capture.

| Case | Browser | Java from pre-request postLogin | Supported interpretation |
|---|---|---|---|
| 1 | 429 | Not run | Chromium post-login traffic is also gated; throttling versus initialization remains unresolved. |
| 2 | 2xx expected JSON | 2xx | Java can succeed from post-login state; verifier/persisted or monitoring-context differences merit later investigation. |
| 3 | 2xx expected JSON | 429 | Transport/context differences are implicated; no particular header or fingerprint cause is isolated. |
| 4 | 2xx expected JSON | Other finite failure | Report that category without a speculative fix. |

Any other browser response stops Java, including redirects, HTML, malformed JSON and invalid
successful envelopes. The requests are sequential: server-side state changes or gating caused by
the first call remain a limitation of interpretation. No production change follows automatically.

Only the first harness failure is reported. Stages include input, authentication, catalog, target
selection, observer setup, `BROWSER_CONTROL_SETUP`, `BROWSER_CONTROL_TRIGGER`, `BROWSER_CONTROL_WAIT`,
`JAVA_COMPARISON_SETUP`, `JAVA_COMPARISON` and cleanup. Categories are `NOT_FOUND`, `AMBIGUOUS`,
`NOT_ACTIONABLE`, `NAVIGATION_TIMEOUT`, `REQUEST_NOT_OBSERVED`, `UNEXPECTED_PAGE_STATE`,
`INTERNAL_INVARIANT`, `SECURITY_INVARIANT`, `PLAYWRIGHT_TRANSIENT`. No exception text is emitted.

## Execution boundary and verification

The test-source driver is excluded from the production jar. Normal `-Run` semantics and the DPAPI
CurrentUser -> binary stdin contract remain unchanged. No secrets enter CLI/environment or reports.
No Spring context, development database, monitoring, Telegram, outbox or subscription mutation is
involved. No request/response dump, HAR, screenshot, trace, storage-state or provider fixture is
written. Schedule JSON is inspected ephemerally for structure; its byte buffer is cleared.

```powershell
.\mvnw.cmd spotless:apply
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp '-Dtest=Schedule429LocalBrowserTest' '-Dschedule429.localBrowserTests=true' test
git diff --check
```

All normal tests use mocks/loopback/synthetic state and make zero real VULCAN calls. Chromium
fixtures verify no page navigation, natural cookie use, all three AJAX headers, Referer comparison,
non-success control gating, duplicate/redirect blocking and output redaction. A production Java
loopback test proves pre-request material is used once and remains immutable despite cookie rotation.

After verification, commit/push and at least 15 minutes without development VULCAN traffic, the
single authorized real invocation is:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-real-smoke.ps1 -InvestigateSchedule429
```

Verification passed: 541 Maven tests reported, zero failures/errors, three optional Chromium
methods skipped. All 11 explicit Chromium cases passed. Formatting and diff checks passed, and
the production jar contains no diagnostic classes. No real VULCAN calls occurred during tests.

## Current result

Harness commit `9fe7972783f7744e9b0e5b8907eebf27dd4d9376` was verified and pushed before
exactly one authorized invocation on 2026-09-05. A controlled quiet window ran from 20:10:14
until after 20:25:14 local time. No VULCAN Java application was running at the boundary checks;
no preliminary provider probe occurred.

Authentication and catalog discovery succeeded (`classCount=26`). The schedule request reached
the endpoint from the existing page, without PlanLekcji navigation, and returned **2xx HTML**:

```text
category=BROWSER_OTHER_FAILURE
result=FAIL
browserSource=BROWSER_CONTEXT_FETCH
browser.statusFamily=2xx
browser.status429=false
browser.contentFamily=html
browserScheduleRequests=1
javaScheduleRequests=0
blockedExtraScheduleRequests=0
javaPermitted=false
javaOutcome=NOT_RUN
decisionCase=NOT_REACHED
retries=0
postLoginCookieCount=4
verifiedCookieCount=4
verificationChangedCookieCount=false
verificationChangedCookieMaterial=false
postLoginRefererContext=OTHER_ALLOWED
verifiedRefererContext=OTHER_ALLOWED
verificationChangedRefererContext=false
browserPageContext=OTHER_ALLOWED
browserRefererMatchesCapturedReferer=true
```

| Header presence | Browser observed | Java synthetic projection |
|---|---|---|
| Verification token | true | true |
| AppGuid | true | true |
| X-Requested-With | true | true |
| Origin | true | true |
| Referer | true | true |
| Content-Type | true | true |
| User-Agent | true | true |
| Accept | true | false |
| Accept-Language | false | false |
| Fetch metadata | false | false |
| Cookie | true | true |
| Cookie count | 4 | 4 |

Browser field-set, timestamp-shape, week-boundary, week-start anchor and URL-encoding booleans
were all true. Java form evidence is synthetic calibration only; no real Java request occurred.
The tested Java path is wired to pre-request `postLogin`, but was not exercised against VULCAN.
Persisted monitoring Referer remains unavailable; the development database was not opened.

The browser control did not obtain the expected schedule JSON. Thus none of cases 1–4 was reached,
and the intended transport comparison is inconclusive. No HTTP 429 occurred on this invocation.
HTML content alone does not identify an authentication page, missing initialization, or another
server response. The HTML body was not inspected or dumped. Required AJAX-header presence and
unchanged observed verification drift do not establish the cause of the prior monitoring 429s.

The invocation exited, no diagnostic Java process remained, and no second invocation or retry
occurred. No secrets, raw provider payloads, request dumps, HAR, screenshots, traces or storage-state
artifacts were persisted. No production compatibility fix, database mutation, PR or monitoring
change was made. Across all three diagnostic invocations, schedule traffic totals one browser
request and zero Java requests; this excludes the separately authorized earlier monitoring runs.


## Independent Java-only baseline

A separate `-InvestigateSchedule429JavaBaseline` mode excludes browser schedule traffic as a
possible server-state/counter/cookie confounder. The existing `-Run` and `-InvestigateSchedule429`
flows and output protocols retain their prior behavior.

The new mode authenticates once using the reviewed browser flow and captures immutable `postLogin`
material. An authentication-only route guard aborts every observed GetPlanLekcjiContext request,
marks `UNEXPECTED_BROWSER_SCHEDULE_TRAFFIC`, and irrevocably blocks Java. Chromium is closed before
catalog verification, so no late browser request can occur during the Java baseline.

Production GetCache/GetTree verification reconstructs its own Java session solely to discover the
authorized catalog. One journal is selected internally. Verifier material is used only for safe
comparison: `applicationBaseChanged`, `refererChangedExact`, `verificationTokenChanged`,
`appGuidChanged`, `cookieMaterialChanged`, `cookieCountChanged`, cookie counts and Referer categories.
Exact cookie-material equality includes ordering/serialization differences; no values are emitted.

One nonrenewable permit then calls production `VulcanClient(VulcanSession.fromMaterial(postLogin))`
for the selected current week. It uses neither verifier-returned material nor persisted database
material. No browser schedule call, retry wrapper, header change, monitoring or database operation
is involved. All browser resources are closed before this call.

**Server-state limitation:** GetCache/GetTree necessarily precede the schedule request. They may
change server-side session state even when all reported client-material comparisons are unchanged.
Closing the browser context is local cleanup, not a portal logout. This baseline excludes browser
schedule traffic and persisted material; it is not a completely untouched server-side login session.

For 429, `retryAfterPresent` reflects the existing exception's parsed optional Duration;
`retryAfterSeconds` reports only its nonnegative whole seconds. No raw header is inspected/output
by the diagnostic. An absent parsed duration cannot distinguish a missing header from one rejected
by the existing parser. HTTP-date conversion is covered by a loopback production-transport test.

| Case | Java outcome | Supported next investigation |
|---|---|---|
| J1 | RATE_LIMITED / 429 | Fresh Java reproduces gating without browser schedule, persisted database session or scheduler; investigate transport/page context later. |
| J2 | SUCCESS | Java can call the endpoint; compare verified/persisted state and monitoring lifecycle later. |
| J3 | HTML, redirect or authentication failure | Investigate session/page-context semantics; no fingerprint conclusion established. |
| J4 | Other finite failure | Report the category only. |

This mode has its own finite allowlist. `result=SUCCESS` means the Java call succeeded; a classified
HTTP failure has `result=FAIL` and `category=BASELINE_COMPLETED`, with J1/J3/J4 identifying the result.
Unexpected browser traffic or other harness/security failures stop before Java and remain outside
J1–J4. No raw exception, response body, cookie name, secret value or provider identifier is output.
DPAPI CurrentUser -> redirected binary stdin remains the sole real credential boundary.

```powershell
pwsh -NoProfile -File .\scripts\vulcan-real-smoke.ps1 -InvestigateSchedule429JavaBaseline
```

Java-only verification passed: 564 Maven tests reported, zero failures/errors, four optional
Chromium methods skipped. All 12 explicit loopback Chromium cases passed, including the
zero-dispatch baseline guard. The 22 new baseline test cases and PowerShell contract tests
passed; formatting and whitespace checks passed. No test made a real VULCAN call.

### Java-only invocation result (2026-09-05)

Harness `2a30ac2f4efa5f17220060e5d4ae80278a80b525` was verified, committed and pushed first.
The development quiet window ran from 20:31:39 until after 20:46:39 local time, with no VULCAN
Java process running at the boundary checks and no preliminary provider probe. Exactly one
new `-InvestigateSchedule429JavaBaseline` invocation then ran and stopped during authentication:

```text
category=TRANSIENT
result=FAIL
stage=AUTHENTICATION
failureCategory=AUTHENTICATION_FAILURE
decisionCase=NOT_REACHED
browserScheduleRequests=0
javaScheduleRequests=0
retries=0
unexpectedBrowserScheduleTraffic=false
browserClosedBeforeVerification=false
javaOutcome=NOT_RUN
java.statusFamily=UNAVAILABLE
java.status429=UNAVAILABLE
retryAfterPresent=UNAVAILABLE
javaMaterialContext=NOT_USED
```

Authentication did not complete, so catalog discovery, session-drift reporting and Java schedule
traffic were not reached. All cookie counts and material-change booleans for this invocation are
unavailable; earlier experiments' values must not be reused. `browserClosedBeforeVerification=false`
means that the pre-verification checkpoint was not reached, not that Chromium was intentionally
left running. The diagnostic exited, managed browser cleanup ran, and no diagnostic Java process
remained.

No J1–J4 case was established. `TRANSIENT` is the finite authentication outcome; no raw exception
was retained, and this evidence does not identify its underlying cause or change the assessment
of the original schedule 429. Retry-After data is unavailable because no Java schedule exception
occurred. No second invocation or retry was made.

Total real schedule requests in this Java-only invocation: zero. No secrets or raw provider
payload/capture artifacts were persisted. No production compatibility fix or PR was created.
The previous experiments above remain the historical record.
