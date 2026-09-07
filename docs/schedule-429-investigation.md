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

### Next evidence source: offline native-request HAR reduction

Automated browser comparisons remain confounded by authentication/UI instability;
the browser control that reached the endpoint returned HTML, not valid schedule JSON.
The next evidence source is a request generated manually by the real Plan Lekcji UI.
This tooling change makes no new endpoint claim and changes no production behavior.

The developer can later log in in their normal browser, open the real schedule UI,
select one authorized class, and export a HAR containing exactly one schedule request.
Keep the raw HAR under `.dev/`; never paste it into chat or commit it. Both raw HARs
and sanitized JSON under `.dev/` are already ignored by the repository. The tool
does not open a browser, authenticate, resolve external resources, or send requests.

Run with PowerShell 7.5 or newer, from the repository root:

```powershell
pwsh -NoProfile -File .\scripts\sanitize-vulcan-schedule-har.ps1 `
    -InputPath .\.dev\vulcan-schedule-native.har `
    -OutputPath .\.dev\vulcan-schedule-native.sanitized.json
```

Omit `-OutputPath` for stdout only. Stdout is sanitized JSON with flat, fixed keys
(for example `response.statusFamily` and `headers.userAgent.present`). The optional
file contains the same report. Existing output files are never overwritten; choose
a new `.dev/*schedule*.json` filename for another reduction. Exit code is zero for
a successful structural reduction, one for a finite input/matching/guard failure.
`SUCCESS` means reduction succeeded, including for a captured HTTP 429 or HTML
response; it does not mean the provider returned a valid schedule.

Output includes status/content/protocol categories, header presence, token nonblank
booleans, normalized protocol-header categories, cookie count, referer category,
origin agreement, form field count/key-set/date-shape/week-relation booleans, and
JSON envelope/array presence and counts. No dates, identifiers, cookie names,
header values, URL strings, or response elements survive reduction. Referer
classification uses path patterns within the request's application prefix and
origin; `OTHER_ALLOWED` means this structural boundary matches, not independent
proof of portal authorization. The schedule endpoint itself is not `PLAN_PAGE`.

Only one exact controller/action path suffix is accepted, and it must use POST.
Matching considers URLs, never text in response bodies. Multiple matching paths
(regardless of method) return `AMBIGUOUS` with a count. Header duplicates and
malformed/duplicate-key JSON fail closed. Form text takes precedence over HAR
decoded params when both exist; params are supported when text is absent. Cookie
count uses the separate HAR cookie array if present, otherwise Cookie header pair
count; a browser export that strips cookies may therefore report zero. Timestamp
relations compare calendar dates without emitting them. JSON parsing of response
text happens only for 2xx responses whose HAR MIME type indicates JSON; HTML text
is never inspected. Base64-encoded JSON content is supported ephemerally.

Every output key and string belongs to an explicit allowlist. Boolean types and
integer counts (0–1,000,000) are checked before file output and stdout. Input size
is capped at 32 MiB and JSON depth at 64. Network/UNC paths and links/junctions are
rejected; on Windows only fixed local drives are accepted. Errors contain only
finite results and never parser messages or paths. No raw intermediate file is
created. Do not enable PowerShell transcription/debug logging while handling a
raw capture; it is outside the sanitizer's output boundary.

The sanitizer never invokes the Java measurement: the production request profiler
uses a loopback HTTP listener, while the sanitizer performs zero network operations.
Schema v2 below supports comparison with a separately saved, validated Java profile.

Tests generate synthetic HAR data in memory and isolated temporary files. Coverage
includes JSON/HTML/429, missing/ambiguous/wrong-method targets, malformed structures,
header/form/cookie omissions, protocol and referer categories, date boundaries,
array counts, output-guard tampering, and CLI stdout/stderr/file leak checks using
fake secret markers. No real HAR was located or processed and no VULCAN request
was made while building this tool. Existing experiments above are unchanged.

Validation: all 47 standalone PowerShell synthetic cases passed. Maven verification
reported 566 tests, zero failures/errors and four optional Chromium tests skipped;
two new JUnit contracts integrate the sanitizer checks. Spotless and
`git diff --check` passed. No Chromium execution is needed for this offline tool.

### Native control and schema v2 fingerprint comparison

The developer supplied a sanitized result from a manually generated native UI
control: **2xx JSON**, with the expected schedule envelope and arrays. Its four-field
form, ISO-T timestamp shapes and Monday–Sunday week semantics match the known
request semantics. Its referer category was `OTHER_ALLOWED`. This is a successful
native control; it does not identify which transport/session difference caused the
earlier Java 429. A richer safe fingerprint comparison is now needed. This update
uses only the supplied sanitized evidence; no raw HAR was inspected or reprocessed.

All sanitizer results now carry fixed integer `schemaVersion=2`, including finite
failures. Existing structural fields remain, with these additions:

- `headers.userAgent.family`: browser family, Java client, other, or absent.
- Client hints: presence only for UA brands; finite mobile and platform categories.
- `headers.accept.profile`: a strict small allowlist of generic, JSON, jQuery JSON,
  and HTML navigation profiles; unknown variants become `OTHER`.
- Leading Accept-Language family and a multiple-language boolean, without regions
  or quality values; Sec-Fetch-Dest category with existing fetch metadata fields.
- Accept-Encoding presence and gzip/br/deflate/zstd/other flags. Flags mean coding
  tokens were listed, even with q=0; they do not describe negotiated compression.
- Priority, Cache-Control, Pragma and DNT presence only, plus bounded header count.

No UA literals, versions, client-hint brands, language lists or unknown header names
are emitted. Java and PowerShell classifiers have shared synthetic conformance
tests. Header count is the captured header-map count, not an HTTP/2 pseudo-header or
wire-frame count. Category equality does not establish equality of original values.

`Schedule429JavaShape` still sends one synthetic request through the untouched
production client/session/adapter/transport to its own loopback server. Its safe
stdout profile adds the same fingerprint facts, form booleans, and separately:
`actualObservedHttpVersion` from the server exchange, and `clientPreferredVersion`
read from the actual configured JDK client used for that request. The diagnostic
reads configuration through test-only reflection; unavailable configuration becomes
`UNKNOWN`. No HTTP configuration is changed. Loopback HTTP/1.1 observation must
never be presented as production TLS/ALPN negotiation.

To create a fresh synthetic Java profile (no credentials or provider input), build
the test classpath, then run the diagnostic main. Only the latter makes one loopback
request; Maven dependency resolution may use the normal dependency cache/repository.
Keep the safe profile under ignored `.dev/` using a new filename:

```powershell
.\mvnw.cmd -B -ntp -DskipTests test-compile dependency:build-classpath `
    '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/schedule-fingerprint-classpath.txt'
if ($LASTEXITCODE -ne 0) { throw 'Synthetic profiler build failed' }
$fingerprintClasspath = 'target/test-classes;target/classes;' + `
    (Get-Content -Raw target/schedule-fingerprint-classpath.txt).Trim()
java -cp $fingerprintClasspath `
    io.github.bohdankordon.vulcanschedulemonitor.devsmoke.Schedule429JavaShape `
    > .dev/vulcan-schedule-java-v2.json
```

The developer can later generate a v2 native reduction using the existing sanitizer
command with a new output filename. An old v1 report cannot supply the new fields;
the tool rejects it rather than guessing. This task does not run that native step.
Compare already-sanitized v2 profiles with no network or raw HAR access:

```powershell
pwsh -NoProfile -File .\scripts\sanitize-vulcan-schedule-har.ps1 `
    -InputPath .\.dev\vulcan-schedule-native-v2.sanitized.json -SanitizedInput `
    -JavaProfilePath .\.dev\vulcan-schedule-java-v2.json `
    -OutputPath .\.dev\vulcan-schedule-comparison-v2.json
```

`-JavaProfilePath` also works with a raw-input reduction when the developer chooses
to run it later. The sanitizer does not launch Java. Both profiles must pass exact
key/type/enum guards; partial or arbitrary extension fields fail closed. The output
retains native facts, prefixes the finite Java profile with `java.`, and adds ten
`comparison.*` booleans plus `comparison.mismatchCount` (0–10). Comparison covers UA
family, Accept profile, language/fetch/client-hint presence, encoding flags, HTTP
preference and form structure. `httpVersionPreferenceCompatible=true` means a known
native protocol equals the configured preference; false also covers unknown data.
Neither value proves production negotiation or server acceptance. Mismatch count
counts these ten comparisons, not individual differing headers or causal factors.

No new real request, authentication, monitoring, protected credential use, or
production compatibility change is part of this update. The sanitizer/comparer
opens no network connections; synthetic Java measurements use loopback only.

Validation: 605 Maven tests reported, zero failures/errors, four optional Chromium
tests skipped. The 39 new Java cases include classifier conformance, untouched
transport measurement, configuration observation, safe CLI output and profile
interoperability. All 87 standalone PowerShell synthetic cases passed, including
comparison counts, schema rejection and leak checks. Spotless and whitespace
checks passed. Only synthetic fixtures and loopback test infrastructure were used.

### Native-session untouched-Java baseline harness (not run against VULCAN)

The developer's native Firefox control succeeded with schedule JSON, and the
offline comparison reported `mismatchCount=7`. Before changing Accept or other
headers, the next proposed experiment uses the successful native request's captured
session material with untouched production Java transport. This task only builds
and tests that harness. It does not inspect the real HAR or execute the experiment.

`scripts/vulcan-native-session-java-baseline.ps1` is separate from the offline
sanitizer. It dot-sources the sanitizer's existing pure helpers; the sanitizer's
CLI entrypoint does not execute and its offline contract is unchanged. The new
script does nothing beyond a finite `NOT_AUTHORIZED` report without `-Run`.

**Future invocation, only after separate explicit real-run authorization**, from
the repository root using PowerShell 7.5+:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-native-session-java-baseline.ps1 `
    -Run -HarPath .\.dev\vulcan-schedule-native.har
```

The real entrypoint accepts only that exact repository-local HAR path. It rejects
UNC/network/mapped drives and reparse points, caps the read at 32 MiB, and builds
the test classpath before reading the HAR. The raw HAR remains local, ignored and
secret; neither it nor its contents should be pasted into chat. No capture copy,
request/response dump, trace, session persistence, or output artifact is created.
Only the build classpath file is written under ignored `target/`.

The source must independently prove exactly one POST to the exact schedule
controller/action, captured 2xx JSON, a successful envelope and both schedule
arrays. Strict JSON parsing rejects duplicate keys; duplicate request headers also
fail closed. Extraction requires HTTPS on the production VULCAN host boundary,
default/443 port, no userinfo, no target query/fragment, and a same-origin/application
Referer. Ambiguous path encodings, traversal and doubled separators are rejected.
Tokens must be nonblank printable header material; Cookie must be an unambiguous
cookie-pair list with unique names. The journal is a canonical positive long.
Exactly four form fields, ISO-T timestamps, Monday–Sunday bounds and an anchor
within the week are required. Unsupported captures fail rather than being repaired.

PowerShell holds the extracted values ephemerally and sends an NSJ1 binary packet
through redirected stdin: fixed magic plus nine length-prefixed UTF-8 fields,
bounded to 64 KiB. No session values enter arguments or environment variables.
The child does not inherit Java agent/debug-option environment variables. Child
output is captured and checked against an exact finite schema; stderr and raw
exceptions are never forwarded. Mutable packet buffers are cleared after use;
immutable runtime strings are not persisted.

The test-source Java driver requires an explicit authorization flag for dispatch, validates
the packet and URL/session/form boundaries again, then reconstructs
`VulcanSession.fromBrowserSession(...)`. A **separate synthetic client** first runs
the actual production form builder/encoder against an in-memory HTTP sink. No
socket or mocking agent is used for that check. All four resulting form values
must match exactly, including midnight timestamp and anchor semantics. A mismatch
returns `FORM_MISMATCH` before a request permit is consumed.

Only after preflight does a distinct, untouched production `VulcanClient` perform
`getWeekSchedule(journalId, dataDate)` through the production adapter and transport.
The real client receives no diagnostic headers, interceptors or HTTP configuration
changes. The in-memory sink is attached only to the separate synthetic client.
There is no browser, authentication, cache/tree lookup, recovery, scheduler, DB,
Telegram, resilience wrapper, or application retry. A nonrenewable atomic permit
allows at most **one raw Java schedule attempt per invocation**. Production redirect
handling remains disabled; loopback tests verify one request for 429 and redirects.
The JDK transport's existing internal behavior is unchanged.

Stdin-boundary tests select the Java driver's validation-only flag, which has no
dispatch path even if a preflight assertion regresses. Tests that exercise actual
dispatch inject a literal loopback endpoint through a package-private test seam;
the real CLI cannot select that seam. Validation-only mode is not exposed as a
raw-HAR PowerShell CLI option and does not authorize any real request.

Safe stdout is schema version 1 with fixed keys: result/category, native-evidence
validation, cookie count/referer category, form-match booleans, attempted/request
count, status/429/content families, Java outcome, Retry-After availability/duration,
and `retries=0`. Unobserved status/content facts stay `UNAVAILABLE`; content type of
non-2xx responses is not exposed by the production exception and is not inferred.
Retry-After is reported only as a nonnegative duration, capped at 31,536,000 seconds
(larger values are saturated). No raw header is emitted. If the supervisor loses
the child's valid report, `CHILD_FAILURE` reports attempted/request count as
`UNAVAILABLE`; the budget must be treated as spent, with no automatic restart.

Future interpretation:

- N1, Java 429: persisted monitoring material is not needed to reproduce the gating;
  Java transport/request context becomes a leading hypothesis. A separate one-header
  Accept experiment can follow, with new authorization.
- N2, Java valid schedule JSON: production Java can call the endpoint with this
  material; investigate connection capture/persistence and session lifecycle before
  changing browser fingerprint headers.
- N3, authentication/redirect/HTML: inconclusive; the captured session may have expired
  or may depend on additional state.
- N4, another failure: retain only its finite category; no compatibility fix follows.

This is **same captured session material, different time**, not a simultaneous A/B.
The source is the native request's cookies/tokens, not its response's rotated cookies.
Server-side state can change after the successful capture. Neither N1 nor N2 alone
proves causality; expired material leaves N3 inconclusive. No real invocation was
performed while implementing this harness, and production request behavior is unchanged.

Validation: 41 new Java test cases and 37 standalone PowerShell cases passed.
Full Maven verification reported 646 tests, zero failures/errors and four optional
Chromium tests skipped. Coverage includes strict HAR/material rejection, stdin
redaction, exact-form preflight, one-shot permits, untouched loopback requests,
HTTP/auth/HTML/transport/protocol outcomes, and Retry-After seconds/date/absence/
malformed/bounded-duration handling. Formatting and whitespace checks passed.
All fixtures were synthetic; VULCAN requests were zero. The real harness was not run.

## Accept-only native-session diagnostic

The subsequently authorized untouched baseline ran once and produced **N1**:
validated native Firefox evidence was 2xx JSON, while Java using the same captured
session material returned 429 / `RATE_LIMITED`, with Retry-After absent, one schedule
request and zero retries. Java transport/request context is now the leading
hypothesis. This remains **same captured session material, different execution
time**; session age and changing server-side state prevent a causal conclusion.

The next isolated variable is explicit `Accept: */*`, matching the native request's
safe `STAR_STAR_ONLY` profile. This implementation has not made a real request.
The following command is for a future, separately authorized invocation only:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-native-session-java-accept-baseline.ps1 `
  -Run -HarPath .\.dev\vulcan-schedule-native.har
```

This is **B only**: at most one Java schedule attempt, zero retries, and no untouched
A request inside the invocation. The original script still selects `UNTOUCHED`;
the sibling selects `ACCEPT_STAR_STAR`. Both use the same raw-HAR validation,
session reconstruction, exact production-form preflight and redirected-stdin
boundary. The raw HAR stays local and secret. There is no browser, authentication,
cache/tree lookup, monitoring, Telegram, or persistence step.

The test-source decorator wraps the existing `JdkClientHttpRequestFactory` within
the production client's RestClient using test-only reflection. It delegates request
creation to that same factory and sets one Accept value. It rejects any other
method/endpoint and cannot be reused or installed twice. It does not replace the
JDK client or CookieManager, change HTTP preference, TLS, redirects or timeouts,
or modify production source. Untouched construction skips the decorator entirely.

Paired loopback captures use identical session/form inputs and the same endpoint.
They prove absent Accept in A, exactly one `*/*` value in B, and equality of every
other captured header name/value, method, URI, protocol and form byte. Only header
name casing and map ordering are normalized. Separate identity checks verify the
original factory, JDK client and cookie handler are retained. Response and budget
tests exercise success, 429, HTML, redirects, authentication and other HTTP errors.
All fixtures are synthetic and all dispatched test requests use loopback.

The finite schema adds `variant=UNTOUCHED/ACCEPT_STAR_STAR` and `acceptInjected`.
The latter is false before customization, true after the decorator applies it,
and `UNAVAILABLE` if the supervisor loses an Accept child's valid report. Injection
does not itself prove successful wire delivery; attempted/request fields remain
separate. Lost-child tests verify one child call, no restart, and unavailable
request counts rather than a fabricated zero. The invocation budget is then spent.
Existing evidence, form, status, outcome and safe Retry-After fields are retained.

Future interpretation, without automatically changing production:

- **A1:** B returns valid 2xx JSON. This strongly supports an effect from explicit
  Accept, but time-varying server state remains a confounder. Review scope before
  considering an operation-specific or transport-wide change.
- **A2:** B returns 429. Accept is weakened as a sufficient explanation, not fully
  refuted: A may have begun a rate-limit window and the material is older. A fresh
  successful native capture would be the next useful control.
- **A3:** B returns HTML, redirect or authentication/session failure. Inconclusive;
  the captured material may be stale.
- **A4:** Another failure. Report only its finite category.

Validation: 14 additional Maven cases (12 focused variant cases and two additional
stdin-boundary cases); full `verify` passed with 660 tests, zero failures/errors,
and four optional Chromium cases skipped. The 37 existing and 11 new standalone
PowerShell cases passed. Spotless and `git diff --check` passed. No developer HAR
was inspected, no VULCAN request occurred, and the real Accept variant was not run.

## Fresh-session result and local cookie-mutation observation

Subsequent authorized runs produced A2 (older captured material with Accept still
returned 429) and then **F2**: a newly captured successful native Firefox request,
followed promptly by untouched Java, produced valid 2xx JSON. F2 validated the
native evidence, used `UNTOUCHED` with no Accept injection, and completed one
schedule request with zero retries. Production Java transport can call the
endpoint. This substantially weakens browser-fingerprint hypotheses; N1 and A2
remain confounded by session age, server-side state and possible rate windows.
Header experiments are no longer the leading direction.

Code inspection confirms that `VulcanSession` owns the JDK CookieManager and
`snapshotMaterial()` reads its current cookie store. The persisted schedule source
loads a session, calls the fetcher, checks the returned scope, and only then calls
`sessions.replace(...)`; replacement snapshots the session. A throwing fetch skips
replacement. Thus a failed response **could** rotate in-memory cookies without
those changes being persisted. Whether a real VULCAN 429 does this remains unproven.

The diagnostic now snapshots locally immediately before the production call and
again in `finally`, including classified failures. Both existing variants use the
same observer. It adds only these finite fields to schema version 1:

- `session.cookieCountBefore` and `session.cookieCountAfter`: bounded counts or
  `UNAVAILABLE`.
- `session.cookieCountChanged` and `session.cookieMaterialChanged`: booleans or
  `UNAVAILABLE`.

Cookie name/value pairs are compared as an in-memory sorted multiset. Pair order
is ignored; additions, removals, value changes and duplicate multiplicity matter.
No names, values, ordering, hashes, per-cookie lengths, tokens, or Set-Cookie
details enter the report. The observation makes no network or persistence calls.
It does not change the request, response classification, permit, or retry policy.
The boolean describes a pair delta across the call; it does not identify the cause
or distinguish a response update from cookie expiry during that interval.

Preflight/permit rejection leaves observation fields unavailable. Transport errors
do not establish whether dispatch occurred: the post snapshot is still taken, but
post fields stay unavailable. This conservatively includes transport failures
after possible dispatch; an unchanged local store is not evidence of no rotation.
Snapshot/parse failures also leave unavailable facts without replacing the original
schedule outcome. In particular, production snapshot material cannot represent an
empty cookie header; complete cookie deletion may therefore be unavailable rather
than reported as zero. A lost child report leaves all cookie observations unknown
and its one-request budget spent, with no restart.

Synthetic loopback tests use the real production session, client, adapter,
transport, JDK request factory and CookieManager. A synthetic 429 with Set-Cookie
rotation is detected after classification as `RATE_LIMITED`. This proves the JDK
mechanism works locally; it does **not** prove the provider rotates cookies on 429.
Coverage also includes unchanged cookies, addition/removal/reordering, HTML,
redirects, auth, 5xx, malformed JSON, transport failure, snapshot failure, both
variants, redaction and the one-request limit. No production source is changed.

### Proposed controlled-idle experiment — not executed

After separate review and authorization, capture another fresh successful native
HAR, leave that captured session idle for approximately five minutes (the normal
monitoring cadence), and invoke the untouched diagnostic exactly once:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-native-session-java-baseline.ps1 `
  -Run -HarPath .\.dev\vulcan-schedule-native.har
```

No preliminary probe, authentication, or Accept experiment belongs in that run.
Report the finite schedule outcome and cookie observations, then stop.

- **T1:** 429 with `cookieMaterialChanged=true` supports the mechanism whereby
  failed-response rotation is discarded by the current persistence lifecycle.
  It would not by itself prove that persisting those cookies fixes the failure.
- **T2:** 429 with `cookieMaterialChanged=false` leaves age/server state implicated,
  but does not support discarded cookie rotation.
- **T3:** Valid 2xx JSON means five-minute idle alone is insufficient to reproduce;
  investigate longer age or the normal verifier/capture/persistence lifecycle.

This implementation task used only synthetic fixtures and loopback traffic. The
developer's raw HAR was not inspected, and no real experiment was executed.

Validation: 38 new Maven cases; full `verify` passed with 698 tests, zero
failures/errors and four optional Chromium cases skipped. Standalone PowerShell
suites passed 37 existing baseline, 11 Accept and nine cookie-observation cases.
Spotless and whitespace checks passed. Real VULCAN requests remained zero.

## Native request prelude — offline schema v3

The developer's controlled tests with fresh successful native Firefox captures
returned untouched-Java 2xx JSON after five, ten and fifteen minutes idle. Each
reported one schedule request, zero retries, and cookie count **4 -> 5** with both
cookie-change booleans true. Idle up to fifteen minutes alone therefore did not
reproduce the earlier 429. Browser-header experiments are deprioritized; the next
question is how native navigation/XHR establishes application state before the
successful schedule request. These results do not identify a required warm-up.

The offline sanitizer adds an explicit `-IncludePrelude` mode. It emits schema v3
and retains all v2 fingerprint fields. Default operation and the pure helper used
by baseline validation remain v2, including their ability to describe failed
native controls. Existing v2 sanitized input/comparison remains supported. V3
sanitized reports also support the existing offline Java-profile comparison.
V2 input cannot be promoted to v3 without raw sequence evidence.

Future local invocation, **not performed during implementation**:

```powershell
pwsh -NoProfile -File .\scripts\sanitize-vulcan-schedule-har.ps1 `
  -InputPath .\.dev\vulcan-schedule-native.har `
  -IncludePrelude `
  -OutputPath .\.dev\vulcan-schedule-native.v3.sanitized.json
```

The existing local-drive/reparse-point/size protections, no-overwrite output policy,
strict JSON/duplicate-key rejection and finite error output apply. Raw captures
remain secret and ignored under `.dev/`; only the allowlisted JSON may be shared.
No provider, browser, authentication, database or messaging operation is added.

V3 requires exactly one POST to the exact schedule action, captured successful
2xx JSON with the expected envelope and both arrays, and the exact known form/week
structure. A 429, HTML or invalid envelope is rejected as a prelude source. The
target must use HTTPS/default port on an allowed VULCAN host. Only the exact same
scheme/host/port is included; external hosts and other origins are excluded.

Requests are sorted by `startedDateTime` (offsets normalized internally), with HAR
array order breaking equal-time ties. Select the last **at most ten requests
within ten seconds** before the target. A target or same-origin entry with missing
or invalid chronological information fails closed. No absolute time is emitted.
`sequenceIndex` starts at zero for the oldest retained entry; `relativeOrder=1`
identifies the nearest retained request. Relative-time buckets are within one
second, over one through five seconds, and over five through ten seconds.

`prelude.sequence` entries contain only fixed method, endpoint, resource and status
categories, bounded order/count fields, cookie presence/counts, Set-Cookie presence/
header counts, and adjacent cookie-change booleans. Endpoint categories are
`PLAN_PAGE`, `PLAN_CONTEXT`, `GET_CACHE`, `GET_TREE`, `REFRESH_SESSION`, `HOME`,
`DZIENNIK`, `STATIC`, and `OTHER_ALLOWED`, based only on strict known path patterns.
Resource categories use fixed HAR/browser metadata or MIME families; unknown
values never become arbitrary output. HTML alone does not establish navigation.
Repeated Set-Cookie headers are counted independently without inspecting values.

Summary fields include `prelude.requestCount`, `containsPlanPage`, `containsGetCache`,
`containsGetTree`, `containsRefreshSession`, `anyResponseSetCookie`,
`anyCookieMaterialChange`, and the nearest retained request's endpoint/method/status/
Set-Cookie presence. `target.cookieCount` and
`target.cookieMaterialChangedFromImmediatelyPreviousRequest` describe the target.
An absent nearest request means absent **within the bounded window**, not proof
that the entire capture has no earlier navigation.

Cookie equality is a case-sensitive in-memory sorted multiset of name/value pairs.
Order-only differences are ignored; value changes, additions/removals and duplicate
multiplicity are detected. Header-derived counts take priority; separate HAR cookie
arrays can provide counts only when a header is missing. Comparisons require both
Cookie headers; otherwise the delta is `UNAVAILABLE`. The first retained entry has
no compared predecessor. `anyCookieMaterialChange` includes the final transition
to the target: true if any observed change exists, unavailable if none is observed
but a transition is unknown, otherwise false. No names, values, hashes, individual
lengths, paths, IDs, query values, bodies, or absolute timestamps enter the output.
Nested entries and aggregates pass an exact output schema before writing/stdout.

Interpretation remains conditional: P1 (navigation, Set-Cookie, then a cookie delta)
would justify reviewing a minimal application-state warm-up experiment; P2
(navigation without visible mutation) leaves server-side changes possible; P3
(no meaningful bounded prelude) favors investigation of connection persistence;
P4 (only cache/tree preparation) favors comparing the normal verifier lifecycle.
Request-start order does not prove response-completion order or that a cookie was
applied before the next request. Path-scoped cookies can also change request-cookie
material without rotation. No prelude is claimed necessary from these facts alone.

Only synthetic fixtures were processed in this task. Real HAR analysis and every
real VULCAN invocation remain deferred to separate authorization.

Validation: 46 new synthetic prelude cases and 87 existing sanitizer cases passed
as standalone PowerShell suites and through Maven. Full `verify` passed with 699
tests, zero failures/errors and four optional Chromium cases skipped, after starting
the local Docker test prerequisite. Spotless and `git diff --check` passed. No real
HAR was processed, and VULCAN requests were zero.

## Persisted normal-connect session baseline (build/test only)

Developer-provided evidence: fresh native-session untouched Java succeeded after
5, 10 and 15 minutes idle. All three calls observed cookie counts 4 -> 5 and
changed cookie material. The native v3 prelude contained zero same-origin requests
in its bounded ten-second window, with no PLAN_PAGE/GetCache/GetTree/RefreshSession
prelude. This does not exclude earlier activity. Neither immediate warm-up nor
ordinary idle up to fifteen minutes currently explains the older 429 results;
browser-header experiments are deprioritized.

The next boundary is normal `/connect` session capture, verification, persistence
and reload. `scripts/vulcan-persisted-session-java-baseline.ps1` is a separate,
explicitly opted-in diagnostic. No HAR is used. Future invocation, **only after
separate real-access authorization**:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-persisted-session-java-baseline.ps1 -Run
```

The future workflow is: start the normal app with monitoring disabled, complete
a fresh normal `/connect` manually, ensure the desired enabled class exists, stop
the app cleanly, then promptly run this one-shot diagnostic and share only its
finite JSON. No credentials or identifiers are command-line inputs. The script
builds the diagnostic test classpath before reading the normal dev CurrentUser
DPAPI master-key file; the decrypted key travels only through redirected stdin.
It does not read the credential smoke bundle or Telegram token. Local fixed-drive,
no-reparse-point path checks and a bounded key-file read reuse the reviewed local
boundary. There are no raw logs, session artifacts, or extracted-secret files.

The Java driver starts a minimal non-web Spring Boot context containing production
JPA repositories, `EncryptedVulcanSecretStore`, its normal AES-GCM/codec, and the
production `VulcanSessionManager`. It excludes application component scanning,
connection/authentication services, browser implementations, monitoring and
Telegram. Unused session-manager auth/verifier dependencies throw immediately.
The fixed local dev PostgreSQL destination matches `dev.ps1`; inherited Spring
configuration, profiles and Java debug/agent environment options cannot override
the diagnostic configuration. Flyway and SQL initialization are disabled, Hibernate
only validates, and PostgreSQL connections have default transactions read-only.
Test-only fixture setup uses a separate writable Testcontainers context.

Preflight conservatively requires exactly one account belonging to an active app
user, that account CONNECTED, and exactly one enabled subscription joining its
own active catalog class. Cross-user ownership, missing/inactive targets, multiple
accounts/targets, and missing/undecryptable session material fail before dispatch.
Only IDs needed for internal resolution are queried; names and Telegram identities
are not selected. Resolution and `loadCurrent` share a read-only repeatable-read
transaction. The stored URI must pass the production HTTPS VULCAN host policy.
The script checks normal dev port 8080 and the child reserves it until shutdown;
it never stops another process. This guard covers the normal dev runner, not a
custom-port deployment: the developer must stop any such instance separately.

The operation is one untouched `VulcanClient.getWeekSchedule` for the Warsaw
current-week Monday; the production adapter builds Monday/Sunday boundaries.
The nonrenewable permit allows at most one call, with no retries, next week,
GetCache/GetTree/RefreshSession, auth, recovery, scheduler or Telegram. No Accept or
other header is injected. Cookie snapshots reuse the canonical multiset boolean
observer, including classified HTTP failures. No `sessions.replace` or other save
is called; the in-memory session is discarded even on success. Lost child output
means the budget is spent, counts/observations unknown, and **never retry**.

Schema 1 uses `variant=PERSISTED_CONNECT_SESSION`, finite `result`/`category`,
`persistedSessionLoaded`, `targetResolved`, `account.connected`,
`account.reconnectRequired`, `form.weekStartValid` and `form.weekEndValid`.
It includes the four `session.cookieCountBefore/cookieCountAfter/cookieCountChanged/
cookieMaterialChanged` fields, request-attempt/count fields, finite Java outcome,
status/content family and 429 flag, bounded Retry-After seconds, and `retries=0`.
Unobserved cookie deltas remain `UNAVAILABLE`. Every emitted key and value is
allowlisted; no cookie identity, token, URL, date, internal ID or raw exception
is included. Response content family remains unavailable when production exception
classification does not expose it.

Future interpretations: **C1**, a fresh persisted session returns 429, favors
investigating `/connect` capture/verifier/snapshot/persistence boundaries; **C2**,
valid JSON success, establishes that this persisted session is schedule-capable
and motivates examining orchestration/spacing/current-plus-next-week sequencing;
**C3**, auth/redirect/HTML, means the session is not usable for this operation at
that time without establishing why; **C4**, target/session preflight failure,
makes no provider request. None is a production fix or proof of causality.

Tests use synthetic material, a disposable PostgreSQL Testcontainer and loopback
HTTP only. They exercise the real encrypted persistence/load round-trip, current
week at the Warsaw/UTC boundary, one-request permit, unchanged stored session after
success and failure, actual JDK 429 cookie rotation, read-only write rejection,
absent schedulers/Telegram/startup provider traffic, stream redaction and finite
output guards. PowerShell contracts also exercise synthetic DPAPI input, default
opt-out, busy-app refusal, and conservative lost-child handling. The developer's
raw HAR, protected files and normal dev database were not inspected. No real
diagnostic was executed in this build/test task.

Validation: 26 new Java cases passed; full Maven `verify` passed with 725 tests,
zero failures/errors and four optional Chromium cases skipped. All 15 standalone
PowerShell contracts passed, along with Spotless and `git diff --check`. All new
database/provider fixtures were disposable Testcontainers/loopback fixtures;
real VULCAN requests were zero.

## Persisted monitoring network sequence (build/test only)

D2, reported by the developer: a **new** normal `/connect` session, with no
earlier diagnostic request, remained idle for approximately five minutes and
then returned valid current-week 2xx JSON through the persisted untouched-Java
baseline. Cookies changed 4 -> 5. Together with immediate persisted-session and
5/10/15-minute native-session success, this strongly deprioritizes simple
first-poll age and browser-header explanations. The next boundary is the actual
CURRENT -> persistence -> spacing -> NEXT monitoring network sequence.

Future command, **not run in this build/test task**:

```powershell
pwsh -NoProfile -File .\scripts\vulcan-persisted-monitoring-sequence-baseline.ps1 -Run
```

After separate authorization: perform a **new** normal `/connect` with monitoring
disabled and one enabled subscription, stop the normal app, wait approximately
five minutes, invoke this diagnostic once, and share only the sanitized output.
Previous diagnostics affected server-side state while discarding local rotations,
so they cannot substitute for that new connection. No HAR or login credentials
are inputs. The existing dev master-key DPAPI/stdin boundary and port-8080 guard
are reused; no process is stopped automatically. Custom-port app instances must
also be stopped by the developer. The offline sanitizer remains offline.

The test-source driver uses the same strict target resolver as the prior baseline:
one active-user account, CONNECTED, and one enabled ownership-matched active
catalog class, with no CLI IDs. It explicitly composes production
`MonitoringScopePlanner`, `MonitoringCycleRunner`, `ScheduleRefreshCoordinator`,
`PersistedAccountWeeklyScheduleSource`, `ResilientWeeklyScheduleSource`,
`RateLimitBackoffGate`, `VulcanSessionManager`, encrypted `VulcanSecretStore`, and
untouched `VulcanClient`/adapter/JDK transport. The planning instant is pinned for
the one cycle to avoid a week-boundary race; Warsaw CURRENT then NEXT must total
exactly two scopes. The scheduled trigger is absent. Recovery is omitted, not
mocked into success: auth/redirect/HTML becomes an account-blocking failure.
No browser, GetCache/GetTree/RefreshSession, Telegram, or real tracking/outbox
service is started. The real tracker/hasher uses an in-memory ActiveChangeStore
and no-op TrackingEventOutbox, discarded at completion.

Production policy defaults are retained: inter-scope spacing 500ms, three possible
resilience attempts, initial transient backoff 1s, fallback rate-limit delay 30s,
and maximum inline rate-limit delay 10s. The cycle's pacing callback records
`spacingAppliedBeforeNext` only after its real delay completes. Retry delays use
the separate resilience callback and are not counted as inter-scope pacing.
A fresh production rate gate supplies `gateInitiallyClear`; account blocking is
observed from the runner's CURRENT result. A no-Retry-After 429 therefore defers
immediately and skips NEXT, without a second CURRENT request.

A diagnostic-only request-factory guard sits below `VulcanClient`. It verifies the
exact allowed schedule URI and POST, then acquires a shared nonrenewable permit
before delegating to the **existing** JDK factory. No request/header/cookie/TLS/
HTTP-version/timeout policy is changed. Two dispatch permits total cover both
weeks and all retries. Attempt three is denied as `BUDGET_EXHAUSTED` and stops the
cycle. Two transient CURRENT attempts may consume both slots, leaving no NEXT
dispatch. Counts conservatively include a permitted attempt even if transport
failure prevents a response; no-response cookie deltas remain unavailable.

Unlike the read-only single-request baseline, this sequence needs transactional
writes. A dedicated outer Spring transaction is marked rollback-only **before**
loading or fetching. Production `sessions.replace` joins it. After CURRENT
success, the diagnostic flushes the encrypted update, clears the JPA cache, and
reloads via the production session manager to establish
`current.sessionPersistedAfterSuccess`. NEXT receives a newly loaded session;
its full material is compared in memory against CURRENT's post-response material
for `next.loadedPostCurrentSession`. URI/token/AppGuid equality is exact; cookie
equality uses a sorted multiset of name/value pairs, ignoring only ordering.
No values, hashes, names or changed-pair details leave memory.

The outer transaction always rolls back, including on HTTP, persistence, budget
or interruption failures. A subsequent independent production load compares the
restored material to the original and reports
`databaseSessionRestoredAfterRollback`. Failure to verify restoration makes the
diagnostic fail; unavailable is not treated as true. PostgreSQL integration tests
also compare original ciphertext, nonce, account and secret-row fields after
rollback, and assert no tracking/change/outbox rows were created. This isolation
intentionally differs from the independently committed production cycle: it tests
persistence/reload within one transaction, not cross-process visibility or commit
timing. Server-side effects of real HTTP requests cannot be rolled back.

Schema 1 uses `variant=PERSISTED_MONITORING_SEQUENCE`. Alongside finite target,
scope, gate, spacing, persistence and rollback facts, `current.outcome` and
`next.outcome` hold production cycle categories; `next.disposition` distinguishes
dispatch from account-blocked/interrupted/budget/not-reached skips. `requests` is
an ordered array of at most two permitted dispatches, each with CURRENT/NEXT,
attempt number, finite HTTP outcome/status/content family, safe Retry-After
duration, and the four cookie count/change observations. This preserves both
CURRENT attempts if a retry consumes NEXT's budget. `retries` counts dispatched
repeat attempts, not a denied third attempt. Unknown child state means the entire
budget is spent, counts/rollback status are unavailable, and **never retry**.
Both Java construction and the PowerShell supervisor enforce closed key/value
schemas. Raw application logs, exception text and child stderr are suppressed.

Future interpretations: M1 CURRENT 429/NEXT skipped reproduces the original
failure inside the monitoring composition; M2 CURRENT success/NEXT 429 focuses
attention on the second scope/sequence and observed persistence boundary; M3 both
succeed means the normal initial network sequence is also functional under the
tested conditions, making historical transient provider state more plausible
than a stable client defect; M4 is another finite failure. None justifies inventing
a header or production fix. No real invocation occurred in this implementation.

Validation: 25 new sequence Java tests passed; the existing 26 persisted-baseline
tests remained green after extracting their shared target resolver. Full Maven
`verify` passed with 750 tests, zero failures/errors and four optional Chromium
cases skipped. All 19 standalone sequence PowerShell contracts, Spotless and
`git diff --check` passed. Tests used only synthetic data, Testcontainers and
loopback; VULCAN requests were zero. No developer HAR or protected file was read.

## Temporary normal /connect blocker before the M-sequence experiment

Two normal `/connect` attempts were reported to fail at approximately
**2026-09-06 14:30 local** and **2026-09-06 14:31 local**, both with
`stage=COOKIE_CONSENT category=TRANSIENT`. The existing stage covered both consent
invocations, so these observations do not identify which invocation or internal
operation failed. This is a separate authentication blocker; it does not change
the historical schedule-429 conclusions above. The M-sequence experiment remains
pending until a fresh successful `/connect` is possible.

The diagnostic-only change distinguishes `INITIAL_PORTAL_CONSENT` from
`POST_DIRECT_LOGIN_CONSENT`, and reports a finite `consentOperation`: discovery,
trust validation, action resolution, accept click, dismiss wait, or final
validation. `NOT_STARTED` and `COMPLETED` delimit each invocation; completion is
reported to the internal observer only when processing and cleanup finish normally,
including absent consent. Failures retain the last operation entered. Nested safety checks and
exception cleanup retain the enclosing operation rather than replacing the
failure location. Non-consent failure logs omit the operation.

The observer accepts only the operation enum, has no return value, and isolates
runtime exceptions. Production uses only an internal enum-state setter. The
sanitized failure formatter accepts only stage/operation/category enums and never
receives exception or page data. Playwright failures remain `TRANSIENT`; existing
safety categories, call ordering, selectors, trusted ancestry/iframe rules,
2000ms discovery and 3000ms dismissal limits, and retry behavior are unchanged.

This change is build/test only: mocks and existing synthetic fixtures, with zero
real VULCAN requests. No real `/connect`, monitoring, M-sequence, or schedule
baseline was run. A real connection attempt requires separate authorization
after review.

Validation: 23 new deterministic diagnostic cases passed; the dedicated
browser-auth/privacy suite passed all 152 cases. Full Maven `verify` passed with
773 tests, zero failures/errors and four opt-in Chromium cases skipped. Spotless
and `git diff --check` passed. Consent constants/selectors/timeouts and existing
frame discovery, ancestry, action-resolution, and safety-rule bodies were also
compared against the pre-change HEAD and remained unchanged.

## Post-accept dismissal observation (2026-09-07, build/test only)

A separately authorized real `/connect` attempt at approximately **2026-09-07
09:58 local** reported `stage=INITIAL_PORTAL_CONSENT`,
`consentOperation=DISMISS_WAIT`, `category=TRANSIENT`. This establishes that the
first privacy-consent action was reached and `accept.click()` returned before
failure during dismissal verification. It does not establish the exact post-click
iframe state, whether a deadline or a state read failed, or that VULCAN changed
its iframe implementation. The M monitoring-sequence experiment remains paused
until normal `/connect` works. This evidence is separate from the historical
schedule-429 findings.

The next diagnostic adds finite dismissal failure kinds: `WAIT_TIMEOUT` for a
deadline from the wait itself, `TRUST_VALIDATION_FAILURE` for existing safety
rejection, `SURFACE_STATE_READ_FAILURE` for a Playwright failure within the
existing blocking callback (owner visibility or frame/trust metadata inspection),
`OTHER_PLAYWRIGHT_FAILURE`, and `OTHER_FAILURE`. `NOT_APPLICABLE` covers a wait
that did not fault. A timeout thrown by a callback read is a read failure, not
misreported as the wait deadline. These observations do not change external
category mapping or replace exceptions; safety failures still take precedence
exactly as before.

Before clicking, best-effort, non-waiting `elementHandles()` calls pin only the
already-known heading and container. At the end, after the existing dismissal
decision and before handle cleanup, a best-effort snapshot checks trust before
DOM inspection and uses those pinned elements and existing trusted owner handles.
It does not re-resolve heading-relative selectors after click. A fixed function
returns only boolean zero-area, pointer-events-none, inert and aria-hidden facts;
no CSS strings, dimensions, coordinates, HTML, attributes or text are returned.
Unexpected keys/types/results are discarded. Heading presence means that the
pinned heading is still connected; container visibility uses the pinned element.
These are sequential observations, not an atomic snapshot or a reconstruction of
an earlier instant. Trust loss or failed reads yield finite unavailable/read-failed
states and cannot change the already-determined consent outcome.

The compact state distinguishes detached frames, hidden owners, visible owners
with zero area, visible owners with pointer-events-none/inert signals, and visible
owners without those signals. `INTERACTIVE` means plausibly interactive based on
these limited checks, not proven hit testing. Heading presence and container
visibility are separately `TRUE`/`FALSE`/`UNAVAILABLE`. `anyOwnerAriaHidden` is
reported separately: aria-hidden alone does not imply non-interactivity. No iframe
and unavailable/read-failed states are explicit. Only these enums reach the logger
on `DISMISS_WAIT` failure. Other failure shapes and ordinary success logging stay
unchanged. Each outer consent invocation resets its observation to avoid stale data.

`noLongerBlocking()` remains untouched: only detachment or an owner becoming
Playwright-invisible permits iframe dismissal. Pointer-events, inert, heading
absence and hidden inner UI are diagnostic facts only. Existing selectors,
ancestry/allowlist checks, 2000ms discovery and 3000ms dismissal timeouts, clicks,
waits and retry policy remain unchanged. Observer and diagnostic-read runtime
exceptions are isolated, including diagnostic handle cleanup.

This implementation uses synthetic mocks and browser-local fixtures only, with
zero real VULCAN requests. No real `/connect` was retried, and no monitoring,
persisted monitoring sequence or schedule baseline was run. A further real
connection attempt requires separate authorization after review.

Validation: 32 new deterministic cases passed; the dedicated mock-based
privacy/auth suite passed all 184 cases. All eight optional Chromium scenarios
also passed when explicitly enabled, using only the in-process loopback fixture
with all other requests blocked. They cover retained inner UI, removed inner UI,
pointer-events-none, inert, aria-hidden, zero area, hidden owners and detachment.
An actual zero-width Chromium owner is invisible and succeeds under the original
rule; the mocked visible/zero-area edge case still fails. Full Maven `verify`
passed with 806 tests, zero failures/errors and five opt-in entries skipped in
the default run. Spotless and `git diff --check` passed. The complete
`noLongerBlocking()` body, selector/timeout constants and existing
discovery/ancestry/trust/action-resolution/form/page safety rules were compared
against the pre-change HEAD and remained unchanged.

## Session capture observation (2026-09-07, build/test only)

A separately authorized real `/connect` attempt at approximately **2026-09-07
10:20 local** reported `stage=SESSION_CAPTURE category=PROTOCOL_FAILURE`; the UI
said the authenticated session could not be verified. This attempt progressed
past the previously failing consent stage and reached session material capture,
which then failed. The exact missing or rejected component is not yet known.
This does not prove that consent is permanently fixed or that authentication
definitively succeeded. It is separate from the historical schedule-429 findings.
M-sequence remains pending until a fresh normal `/connect` succeeds.

The diagnostic accumulator counts only already-allowlisted observed requests.
Header reads occur in their existing order, once each. Their nonblank-presence
booleans are recorded immediately; no request URI, header value, exception,
cookie name/value, or hash enters the accumulator. Incomplete observations are
still discarded from the production capture list. Allowed-request counts saturate
at six; complete-request and candidate counts saturate at two. The individually
seen header facts are aggregate facts across requests. Only an actual observation
with all three required headers increments the complete count and sets
`sawAllRequiredHeadersTogether`; distributed headers never form a candidate.

Capture still scans newest-to-oldest, tries each eligible complete candidate once,
and stops at the first valid material. Candidate counts describe eligible
candidates actually reached. Candidates-with-cookies counts describe candidates
that reached the existing cookie-filter step and produced a nonblank same-origin
cookie header. No additional cookie filtering is performed for candidates rejected
earlier. After material construction rejects a candidate, a read-only URI diagnosis
reuses the exact existing `normalize()` and `requireSameOrigin()` validators to
distinguish base/Referer rejection from other material rejection. The constructor
and all its acceptance checks remain unchanged; diagnosis cannot accept a candidate.

After exhausted candidate processing, the deterministic aggregate precedence is
`MATERIAL_REJECTED` > `REFERER_REJECTED` > `APPLICATION_BASE_REJECTED` >
`NO_MATCHING_COOKIES` > `OTHER_PROTOCOL_FAILURE`. If no eligible candidate failed,
zero allowed requests yields `NO_ALLOWED_REQUEST`; allowed requests without a
complete observation yield `NO_COMPLETE_REQUEST`. An unexpected abort before
exhaustion with complete observations yields `OTHER_PROTOCOL_FAILURE`, so an
earlier handled rejection cannot conceal it. A returned session material clears
the failure classification to `NOT_APPLICABLE`; no success diagnostic is logged
and reaching the capture stage is not itself an authentication-success claim.

Cookie lookup is unchanged: the last complete observation selects the one browser
context lookup, even when older candidates are later examined. Its returned list
size is converted immediately to `ZERO`, `ONE`, `TWO_TO_FOUR`, or `FIVE_PLUS`.
`UNAVAILABLE` means no completed lookup, including no complete observation or a
lookup exception. This count is the lookup result count, not a claim that every
cookie matched every candidate. No cookie names, values, lengths or hashes are
diagnostic output.

Only `SESSION_CAPTURE` failure logging gains the finite `captureFailure`,
`allowedRequests`, `completeRequests`, four header-presence booleans, `candidates`,
`candidatesWithCookies`, and `cookieCount` fields alongside the existing category.
The logger receives only enums and booleans. Non-capture failure messages and
ordinary success logging retain their existing shapes. Failure categories,
allowlisted URIs, application-base derivation, same-origin/path requirements,
cookie selection, candidate order, login timing and retries are unchanged.

This is build/test only. Tests use synthetic material and mocked browser events;
no real VULCAN request, `/connect` retry, monitoring, M-sequence, or schedule
baseline was run. Another real attempt requires separate authorization after review.

Validation: 55 new deterministic cases passed in full Maven `verify`, which
reported 861 tests, zero failures/errors and five optional Chromium entries
skipped. The dedicated capture/auth/privacy test run also passed. No Chromium
test was enabled for this Java-only observation change. Tests cover incomplete
and distributed headers, bounded buckets, each rejection kind, deterministic
precedence, unexpected aborts, unchanged newest-first selection and last-complete
cookie lookup, successful material capture, finite logger arguments and secret
redaction. Spotless and `git diff --check` passed. Source comparison confirmed
unchanged base derivation, same-origin rules, material constructor/validators,
and cookie-selection loop apart from its count observation.

## Post-success session fidelity investigation (2026-09-07, offline only)

The first separately authorized real persisted monitoring-sequence diagnostic
returned `result=FAIL category=SEQUENCE_COMPLETED`. The persisted session loaded,
the target resolved with two scopes, and the gate was initially clear. **CURRENT
itself returned 2xx JSON SUCCESS**, with cookies changing from 6 to 7 and both
cookie-count and cookie-material change observations true. The account was not
blocked after CURRENT. Immediately after production session persistence, flush,
clear and reload, `current.sessionPersistedAfterSuccess=false`.

The harness deliberately translated that invariant failure into
`current.outcome=INTERRUPTED` and `next.disposition=SKIPPED_INTERRUPTED`. There was
one schedule request, zero retries, no spacing before NEXT, and
`next.loadedPostCurrentSession=UNAVAILABLE`. Rollback verification reported
`databaseSessionRestoredAfterRollback=true`. This was neither M1 current-429,
M2 next-429 nor M3 complete two-request success. It is a new post-success
persistence/session-fidelity question, separate from the historical first-request
429 conclusion. The old observation cannot identify the differing material field
or attribute the mismatch to JPA, encryption, codec or cookie reconstruction.

Although rollback restored the database, the successful real CURRENT response
already mutated live cookie state. **Do not reuse that session for another real
experiment. A new successful `/connect` is required first**, under separate
authorization. M-sequence remains pending. This offline change does not run
`/connect`, monitoring, M-sequence or any provider baseline.

### Safe comparison boundaries for a future authorized run

The existing persistence invariant now obtains a finite field comparison and
uses its conjunction for the same pass/fail decision. It reports:

```text
current.persistence.applicationBaseSame
current.persistence.refererSame
current.persistence.verificationTokenSame
current.persistence.appGuidSame
current.persistence.cookieMaterialSame
current.persistence.expectedCookieCount
current.persistence.actualCookieCount
current.persistence.cookieCountChanged
current.liveCookieTopology.totalCookieCount
current.liveCookieTopology.duplicateNamePresent
current.liveCookieTopology.duplicateNameDifferentPathPresent
current.liveCookieTopology.duplicateNameDifferentDomainPresent
current.materialRoundTrip.cookieMaterialSame
current.materialRoundTrip.cookieCountBefore
current.materialRoundTrip.cookieCountAfter
```

Comparisons use secrets only in memory. Cookie material uses the same sorted
multiset of trimmed pairs as the original invariant: order alone is ignored,
duplicate pairs remain significant. The topology seam is package-private in
`vulcan.session`; a test-source adapter exposes only finite observations to the
devsmoke harness. Cookie names are compared case-insensitively, domains likewise,
and paths exactly, consistent with JDK cookie identity comparisons. No cookie
store, identity, value or hash escapes. Counts saturate at 1000, meaning 1000 or
more; equality and count-change decisions still use the full data, so equal
saturated counts do not imply equality. Unreached or failed optional observations
remain `UNAVAILABLE`, never a false zero. Both Java and PowerShell report guards
allow only these fixed keys and boolean/bounded-integer/`UNAVAILABLE` values.

After successful CURRENT response processing, the harness observes live topology
and locally reconstructs `snapshotMaterial -> fromMaterial -> snapshotMaterial`
before production persistence. The reconstructed instance is discarded; it is
never used for requests or persistence. Optional local observation errors cannot
replace the existing outcome. The original reload mismatch still interrupts
before NEXT. No request, retry, wait, cookie-selection rule, acceptance criterion,
production session format or persistence operation is changed.

### Structural hypothesis and proposed follow-up designs

By construction, `snapshotMaterial()` writes only `name=value` pairs, discarding
Path and Domain (and other attributes). `seedCookies()` reconstructs every pair
at the application path. The deterministic test uses a real JDK CookieManager
configured by VulcanSession and one loopback Set-Cookie response: two cookies
share a name, have different values, and have application-path versus root-path
identities. This isolates the structural hypothesis without provider traffic.
It does **not** establish that the real 6-to-7 response had duplicate-name cookies;
that requires future safe topology evidence. Attribute loss begins when the live
store is flattened; same-name identity collapse can then occur during reseeding.

Offline results: the loopback different-path case retains both live identities,
then reports `cookieMaterialSame=false`, `cookieCountBefore=2`,
`cookieCountAfter=1` for the local round-trip. Unique-name cookies survive with
two cookies; same-name/same-path Set-Cookie deterministically replaces the old
value and survives with one cookie. An in-memory JDK CookieManager also confirms
duplicate-name/different-domain topology without network access. Ordering-only
changes pass the original multiset comparison.

SecretPayloadCodec preserves the exact cookie-header string (including spacing
and duplicate pairs) and identical re-encoded bytes. The real AES encrypted store,
tested with an in-memory repository, preserves all material fields exactly for
both unique and duplicate names, including an existing-row update. PostgreSQL
Testcontainers tests likewise preserve exact material after flush/clear and a
separate transaction read. Only subsequent VulcanSession reconstruction collapses
the duplicate names; round-trip-safe material remains equal through that step.
Thus the reproduced loss is in live-cookie flattening/reseeding, before PostgreSQL
or encryption, not a codec or database alteration of the material string. This
isolates the synthetic case; the exact cause of the real mismatch remains unmeasured.

The loopback/Testcontainers M harness reproduction reports CURRENT provider
`outcome=SUCCESS`, all four non-cookie persistence comparisons true,
`cookieMaterialSame=false`, expected/actual counts 2/1, duplicate-name/different-path
true, different-domain false, and the same local round-trip loss. It still produces
`current.outcome=INTERRUPTED`, skips NEXT with one request and zero retries, and
verifies rollback restoration. Its finite report passes both Java and PowerShell
output guards without exposing synthetic secrets.

No production fix is implemented. A later migration-compatible design could add
an explicit, versioned cookie list to session material, retaining name/value,
Path, Domain, Secure and HttpOnly, plus expiry semantics only where needed for
correct reconstruction. A new codec version could read legacy v1 payloads as
legacy header material and write v2 after validated migration or a fresh capture.
An alternative is a versioned optional structured-cookie section alongside the
legacy header during migration, with one deliberately specified authoritative
representation and no silent fallback after malformed structured data. Either
design must preserve multiple same-name identities, define host-only/domain and
expiry behavior, and handle browser captures that initially lack full Set-Cookie
metadata. Missing attributes must not be invented as observed facts. Do not blindly
serialize Java HttpCookie internals. Existing encrypted sessions, key/AAD version
handling, safe rollback and secret-free logging all need explicit compatibility
tests before such a fix is authorized.

Validation: 16 new deterministic JUnit cases passed. The dedicated
session/codec/encrypted-store/persistence/devsmoke suite passed 57 tests with no
failures, errors or skips; the PowerShell report contracts passed all 94 cases.
Full Maven `verify` passed 877 tests with zero failures/errors and five optional
Chromium entries skipped. Chromium was not enabled for this session-only change.
Spotless and `git diff --check` passed. Source comparison confirmed that removing
the new package-private observation seam leaves VulcanSession unchanged; production
material, codec, encryption, persistence and request logic were not modified.
VULCAN requests = 0 for this work: synthetic loopback/Testcontainers only, no
developer secrets or developer database rows inspected, and no real `/connect`,
monitoring, M-sequence or schedule baseline run.
