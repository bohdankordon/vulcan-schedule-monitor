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

Pending commit, push and completion of the quiet window. No real invocation has occurred
in this revision yet. No production compatibility fix is implemented.
