# VULCAN schedule investigation and final validation

## Confirmed defect and scope

A real successful schedule response created two cookies with the same name and different paths.
The previous session format retained only `name=value` pairs, and reconstruction assigned every
pair the application path. This collapsed distinct cookie identities. The defect is in session
representation/reconstruction; it is not evidence that encryption or PostgreSQL corrupts data.

The separate real confirmation recorded CURRENT as 2xx JSON SUCCESS, live cookies increasing
from 6 to 7, duplicate-name/different-path present, and duplicate-name/different-domain absent.
Both the local material round-trip and persistence/reload changed 7 cookies to 6. Application base,
Referer, verification token and AppGuid were each equal; only cookie material differed.
`current.sessionPersistedAfterSuccess=false` intentionally interrupted the diagnostic before NEXT.
Database rollback restoration was verified. No cookie identities or values are recorded here.

A loopback reproduction independently established the same failure: two same-name cookies with
different paths became one after snapshot/reconstruction. Exact codec, AES and PostgreSQL controls
preserved the supplied material before reconstruction. Together with the safe real topology, this
confirms the cookie persistence defect. Earlier observations of 6 -> 7 alone did not establish it.

This is separate from the historical initial HTTP 429 responses. Header comparisons, native-session
baselines and the early browser-versus-Java experiments did not establish their original cause.
Some early experiments stopped before schedule traffic because of harness prerequisites; those
failures were not provider outcomes. No speculative header, navigation, timing or retry fix remains.

## Structured session format

Modern `VulcanSessionMaterial` contains an immutable list of `VulcanCookieMaterial` records:
`name`, `value`, nullable `path`, nullable `domain`, `secure`, and `httpOnly`. This list is the
persistence authority. Equality ignores order and compares all retained fields. Duplicate effective
JDK identities are rejected, while same-name cookies with different paths or domains remain distinct.
`toString()` and validation exceptions do not render cookie or other session values.

Playwright capture uses the existing `context.cookies(observedApplicationUrl)` selection and retains
its metadata. It does not capture unrelated domains or flatten modern cookies into a header.
Live snapshots copy the current JDK cookie store, including identities added by successful responses.
Structured reconstruction creates individual version-0 `HttpCookie` objects and adds them directly
to the store. Actual paths and domains are retained. A null domain remains null and is associated
with the application URI; a supplied domain, including a leading dot, is retained (case normalized).
A missing path remains unknown rather than inventing an original path. Secure and HttpOnly survive.
JDK request matching, with the existing same-origin/application-path transport checks, remains in use.

Expiry is deliberately outside this fix: reconstructed cookies retain the project's previous effective
session-cookie persistence lifetime. Neither Playwright absolute expiry nor JDK relative Max-Age is
serialized. In particular, no persisted relative Max-Age is restarted at each reload. Precise expiry
and browser-specific attributes would require a separate deliberate format/behavior decision.

Validation limits are 1..1000 cookies, UTF-8 name <=256 bytes, value <=16384, path <=4096 and domain
<=253. Names must be valid cookie tokens. Controls, CR/LF, malformed Unicode and invalid path/domain
syntax are rejected with fixed messages. Non-null paths start with `/`; domains contain no URI,
userinfo or port syntax. Duplicate effective identities are rejected instead of partially accepted.

### VSM2 and VSM1 migration

VSM2 is a bounded binary plaintext payload within the existing encrypted envelope:

1. Four-byte `VSM2` magic.
2. Four length-prefixed UTF-8 fields: application base, Referer, verification token and AppGuid.
3. Signed four-byte cookie count.
4. Each cookie: length-prefixed name, value, path and domain, then Secure and HttpOnly bytes (0 or 1).
   Only path/domain permit the -1 null length.

The decoder bounds the total payload (32 MiB), individual fields and cookie count, checks UTF-8,
rejects invalid flags, truncated records, malformed identities and trailing bytes, and fails closed.
Malformed VSM2 never falls back to VSM1. AES-256-GCM envelope/AAD versions and database schema are
unchanged; no ciphertext rewrite or database migration is needed.

Existing VSM1 decrypts and decodes to `LEGACY_HEADER` material. Its missing attributes cannot be
recovered, so reconstruction deliberately uses the previous seeding semantics. The next successful
live snapshot is STRUCTURED; normal successful persistence writes VSM2. Direct legacy compatibility
encoding remains VSM1 and does not fabricate metadata. Already degraded legacy sessions cannot
recover lost identities; a fresh authorized connection was required for the final real validation.

## Safe support diagnostics retained

Authentication diagnostics cover failures separate from the cookie defect. They remain because they
provide useful support information without disclosing session contents or changing acceptance rules.

- Outer stages distinguish initial portal consent from post-direct-login consent.
- A finite consent operation identifies discovery, trust/action validation, click, dismissal wait or
  final validation. A finite dismissal failure/state distinguishes timeout, inspection/trust failures
  and safe structural post-click observations. Fixed evaluation on already trusted owners returns
  only approved booleans, never DOM/CSS/geometry values.
- Session capture records bounded request/candidate/cookie counts, aggregate header-presence booleans
  and a deterministic finite rejection category. Separate requests containing separate headers never
  become a complete candidate. Raw incomplete header values are not retained for diagnostics.
- The production failure formatter accepts only finite stage/operation/state/category/count-bucket
  values and booleans; the logger writes that fixed sanitized line. Tests use this same formatter.
  It excludes exception text/stacks, URLs, cookie identities, credentials, tokens and AppGuid values.

Consent observers are isolated from control flow. Selectors, trust/ancestry checks, the 2-second
consent discovery and 3-second dismissal timeouts, and the detached-or-owner-hidden completion rule
remain unchanged. Cookie selection, capture candidate order, monitoring spacing, rate gate, retries,
tracking, outbox and baseline notification policy are unchanged by this cleanup.

Historical authentication evidence remains limited to what was measured:

| Local time | Safe observation | Interpretation |
|---|---|---|
| 2026-09-06 ~14:30 and ~14:31 | COOKIE_CONSENT / TRANSIENT | Two normal connection attempts failed; invocation/operation unknown. |
| 2026-09-07 ~09:58 | INITIAL_PORTAL_CONSENT / DISMISS_WAIT / TRANSIENT | First consent click completed; dismissal verification failed; exact iframe state unknown. |
| 2026-09-07 ~10:20 | SESSION_CAPTURE / PROTOCOL_FAILURE | Consent was passed on this attempt; session capture failed; missing/rejected component unknown. |

These do not prove that consent is permanently fixed or that reaching capture alone authenticates a
session. They are not explanations for historical schedule 429s.

## Maintenance tools and regression coverage

The offline HAR sanitizer is retained at `scripts/diagnostics/sanitize-vulcan-schedule-har.ps1`, with
its prelude library under `scripts/diagnostics/lib/`. It reads an explicitly supplied local input and
writes a finite/redacted report; it cannot contact a provider. Its synthetic PowerShell tests cover
both raw-input and already-sanitized schemas. Raw HAR/session files must never be committed.

The ordinary `scripts/vulcan-real-smoke.ps1` harness and its tests are restored to their PR #12/main
behavior. Its explicit `-Run` authorization remains required for real traffic. No diagnostic or
smoke run is authorized by this document. One-off native-session, persisted-session, Accept-header
and M-sequence executables/report contracts are removed now that their investigation is complete.

Their valuable regression was extracted into `SessionPersistencePostgresTests`: a narrow test-only
Spring context drives the real production weekly source against loopback, persists a rotated session,
flushes/clears, reloads separately, and checks that CURRENT/NEXT retain both cookie identities without
retry. Another case verifies actual JDK schedule/root request routing after response, encryption,
PostgreSQL reload and reconstruction. No live configuration or developer database is consulted.

Other retained tests cover same-name/different-domain state, order-independent structured equality,
VSM2 exact codec/AES/replacement fidelity, VSM1 decoding/upgrade, malformed payload rejection and
secret-marker redaction. Optional local Chromium fixtures exercise trusted privacy iframe states and
the actual Playwright cookie conversion path with two same-name/different-path cookies.

`SessionMaterialTestSupport` and `CookieTopologyObservation` now live only in test sources. They
compare secret values in memory but emit only booleans/bounded counts. The flattened rendering helper
is test-only; there is no public production diagnostic accessor or CookieManager exposure.

## Final branch artifact audit

The following inventory covers every file in the pre-cleanup `main...a3e9a64` diff. Names identify
historical artifacts, not runnable instructions. Files removed from the branch remain in Git history.
KEEP means required production behavior or durable regression/support value; REMOVE means exhausted
experiment machinery; SIMPLIFY / MOVE means its useful behavior survives in a smaller boundary.
Java paths below are relative to the common `io/github/bohdankordon/vulcanschedulemonitor` package.

| Original file | Decision | Final disposition |
|---|---|---|
| `docs/schedule-429-investigation.md` | SIMPLIFY / MOVE | Consolidated evidence, migration, cleanup decisions and final validation. |
| `scripts/lib/vulcan-har-prelude.ps1` | SIMPLIFY / MOVE | Moved beneath scripts/diagnostics; durable offline sanitizer. |
| `scripts/sanitize-vulcan-schedule-har.ps1` | SIMPLIFY / MOVE | Moved beneath scripts/diagnostics; durable offline sanitizer. |
| `scripts/tests/sanitize-vulcan-schedule-har.Tests.ps1` | KEEP | Offline sanitizer regression; updated moved path. |
| `scripts/tests/sanitize-vulcan-schedule-prelude.Tests.ps1` | KEEP | Offline sanitizer regression; updated moved path. |
| `scripts/tests/vulcan-native-session-cookie-observation.Tests.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/tests/vulcan-native-session-java-accept-baseline.Tests.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/tests/vulcan-native-session-java-baseline.Tests.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/tests/vulcan-persisted-monitoring-sequence.Tests.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/tests/vulcan-persisted-session-java-baseline.Tests.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/tests/vulcan-real-smoke.Tests.ps1` | SIMPLIFY / MOVE | Investigation additions removed; original PR #12/main harness preserved. |
| `scripts/vulcan-native-session-java-accept-baseline.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/vulcan-native-session-java-baseline.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/vulcan-persisted-monitoring-sequence-baseline.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/vulcan-persisted-session-java-baseline.ps1` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `scripts/vulcan-real-smoke.ps1` | SIMPLIFY / MOVE | Investigation additions removed; original PR #12/main harness preserved. |
| `main/vulcan/connection/playwright/BrowserAuthStage.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/BrowserCookieObservation.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/PlaywrightVulcanBrowserAuthenticator.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/PrivacyConsentDismissDiagnostics.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/PrivacyConsentDismissFailureKind.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/PrivacyConsentDismissObservation.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/PrivacyConsentDismissState.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/PrivacyConsentOperation.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/SessionCaptureDiagnostics.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/SessionCaptureFailureKind.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/SessionCaptureObservation.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/VulcanPrivacyConsent.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/playwright/VulcanSessionCapture.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `main/vulcan/connection/secret/SecretPayloadCodec.java` | KEEP | Required structured session / VSM2 behavior; test-only accessors removed where applicable. |
| `main/vulcan/session/CookieTopologyObservation.java` | SIMPLIFY / MOVE | Moved to test sources; production topology seam removed. |
| `main/vulcan/session/VulcanCookieMaterial.java` | KEEP | Required structured session / VSM2 behavior; test-only accessors removed where applicable. |
| `main/vulcan/session/VulcanSession.java` | KEEP | Required structured session / VSM2 behavior; test-only accessors removed where applicable. |
| `main/vulcan/session/VulcanSessionMaterial.java` | KEEP | Required structured session / VSM2 behavior; test-only accessors removed where applicable. |
| `test/devsmoke/HarSanitizerScriptTest.java` | KEEP | Offline sanitizer regression; updated moved path. |
| `test/devsmoke/MonitoringSequenceFidelityReportTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/MonitoringSequenceReport.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionAcceptDecorator.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionAcceptTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionBaselineInput.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionBaselineReport.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionBaselineTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionCookieObservation.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/NativeSessionCookieObservationTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/PersistedBaselineReport.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/PersistedDiagnosticTarget.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/PersistedMonitoringSequenceTest.java` | SIMPLIFY / MOVE | Loopback/PostgreSQL production regression extracted into SessionPersistencePostgresTests; report/CLI tests removed. |
| `test/devsmoke/PersistedSessionBaselineTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429Budget.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429Failure.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429Fingerprint.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429FingerprintTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429InvestigationTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429JavaBaselineBudget.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429JavaBaselineReport.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429JavaBaselineTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429JavaShape.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429Report.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/Schedule429Structure.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/SequenceDispatchBudget.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/SmokeScriptTest.java` | SIMPLIFY / MOVE | Investigation additions removed; original PR #12/main harness preserved. |
| `test/devsmoke/VulcanNativeSessionJavaBaseline.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/VulcanPersistedMonitoringSequence.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/VulcanPersistedSessionJavaBaseline.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/VulcanSchedule429Investigation.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/devsmoke/VulcanSchedule429JavaBaseline.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/integration/AccountAwareMonitoringEndToEndPostgresTests.java` | KEEP | Session, codec, encryption or persistence regression; test helper references updated. |
| `test/vulcan/connection/DefaultVulcanSessionVerifierWireMockTest.java` | KEEP | Session, codec, encryption or persistence regression; test helper references updated. |
| `test/vulcan/connection/VulcanConnectionPostgresTests.java` | KEEP | Session, codec, encryption or persistence regression; test helper references updated. |
| `test/vulcan/connection/persistence/PersistedBaselineConfiguration.java` | SIMPLIFY / MOVE | Narrow package-private SessionPersistenceTestConfiguration, test sources only. |
| `test/vulcan/connection/persistence/SessionMaterialStorageFidelityTest.java` | KEEP | Session, codec, encryption or persistence regression; test helper references updated. |
| `test/vulcan/connection/playwright/PlaywrightVulcanBrowserAuthenticatorTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/playwright/PrivacyConsentDiagnosticsTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/playwright/PrivacyConsentDismissDiagnosticsTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/playwright/PrivacyConsentDismissLocalBrowserTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/playwright/Schedule429Browser.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/vulcan/connection/playwright/Schedule429BrowserTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/vulcan/connection/playwright/Schedule429LocalBrowserTest.java` | REMOVE | Exhausted one-off experiment, report or driver contract. |
| `test/vulcan/connection/playwright/SessionCaptureDiagnosticsTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/playwright/StructuredCookieCaptureLocalBrowserTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/playwright/VulcanSessionCaptureTest.java` | KEEP | Structured capture or finite authentication support and regression coverage. |
| `test/vulcan/connection/secret/SessionPayloadV2Test.java` | KEEP | Session, codec, encryption or persistence regression; test helper references updated. |
| `test/vulcan/session/SessionFidelityDiagnostics.java` | SIMPLIFY / MOVE | Renamed SessionMaterialTestSupport / Test; regression support only. |
| `test/vulcan/session/SessionFidelityDiagnosticsTest.java` | SIMPLIFY / MOVE | Renamed SessionMaterialTestSupport / Test; regression support only. |
| `test/vulcan/session/StructuredCookieMaterialTest.java` | KEEP | Session, codec, encryption or persistence regression; test helper references updated. |

## Cleanup verification

A clean offline Maven verification completed with 652 tests, zero failures/errors and two optional
browser classes skipped. Running those classes explicitly passed all nine Chromium loopback cases.
The retained PowerShell suites passed 87 HAR contracts, 46 prelude contracts and the ordinary smoke
contracts using synthetic temporary state. The extracted PostgreSQL fixture closes its test context
and bounds its connection pool so it does not exhaust the shared Testcontainers database.
`git diff --check` passed; no raw HAR/session/browser artifacts or `.dev` files are tracked.
Provider requests during cleanup were zero. No real connection, monitoring or M-sequence was run.

## Final real validation (separately authorized; not rerun during cleanup)

The production fix was validated with a fresh session in a successful real M3 sequence:

| Observation | Result |
|---|---|
| Overall | SUCCESS / SEQUENCE_COMPLETED |
| CURRENT | 2xx JSON SUCCESS; cookies 6 -> 7 |
| Structured local round-trip | 7 -> 7; cookieMaterialSame=true |
| Persistence/reload | expected=7, actual=7; cookieMaterialSame=true; all non-cookie fields equal |
| Post-CURRENT invariant | current.sessionPersistedAfterSuccess=true |
| Spacing | spacingAppliedBeforeNext=true; 500 ms |
| NEXT session | next.loadedPostCurrentSession=true |
| NEXT | 2xx JSON SUCCESS; cookies 7 -> 7 |
| Request budget | totalScheduleRequests=2; retries=0 |
| Rollback | databaseSessionRestoredAfterRollback=true |

Ordinary application runtime was subsequently validated with monitoring enabled for exactly one
class. On 2026-09-07 at 12:44 local, scheduler cycle #1 started with targets=1/scopes=2 and completed
with successes=2, failures=0 and stoppedEarly=false. Telegram delivered the expected current-week
and next-week baseline notifications. Two dispatch runs each reported claimed=1, delivered=1,
retried=0 and dead=0. This matches the existing `NotificationEventType.BASELINE_ESTABLISHED` model;
silent baselines would be a separate product decision.

At 12:49 local, scheduler cycle #2 again reported targets=1/scopes=2, successes=2, failures=0 and
stoppedEarly=false. No new Telegram dispatch followed the unchanged schedules.

These real results validate the scheduler, two-week planning, CURRENT/NEXT fetches, session rotation,
structured persistence, tracking baselines, transactional outbox, recipient routing, Telegram delivery
and unchanged-cycle deduplication together. Cleanup verification is offline/loopback/Testcontainers
only and does not repeat those operations.

Historical 429 responses were not reproduced after fresh-session validation and the cookie identity
fix; their original cause remains unproven.

## 2026-09-10 production incident and sanitized diagnostics

### Safe operational facts

During production monitoring activation on `acer-server` (running commit `9dafbb345a74bc11a4a58ff80fd513be1fab9404`),
two consecutive controlled monitoring attempts were performed:

1. **Attempt #1**:
   - Exactly one real VULCAN account connected (status `CONNECTED`, `remember_credentials=false`, 26 active catalog classes).
   - Exactly one monitoring subscription configured.
   - Initial scheduler cycle: `targets=1`, `scopes=2`.
   - The first weekly schedule scope fetch returned HTTP 429 and was classified `DEFERRED_RATE_LIMIT`.
   - Fail-closed safety stopped the cycle: `successes=0`, `failures=1`, `stoppedEarly=false`.
   - Zero tracking rows and zero notification outbox rows were written; account status remained `CONNECTED`.
   - Monitoring was immediately disabled.

2. **Attempt #2** (after ~74 minutes of quiet provider cooldown):
   - Prechecks verified zero provider traffic had occurred during the quiet cooldown window.
   - Initial scheduler cycle: `targets=1`, `scopes=2`.
   - The first weekly schedule scope fetch returned HTTP 429 and was again classified `DEFERRED_RATE_LIMIT`.
   - Fail-closed safety stopped the cycle: `successes=0`, `failures=1`, `stoppedEarly=false`.
   - Zero tracking rows and zero outbox rows were written; account status remained `CONNECTED`.
   - Monitoring was immediately disabled.

### Root cause remains unproven

The root cause of these schedule HTTP 429 responses is **not yet known**. The following are active hypotheses,
not proven conclusions:

- **IP-level rate limiting**: provider rate gate applied to the server's egress IPv4.
- **Tenant-level rate limiting**: provider throttling schedule endpoints for the specific school tenant.
- **Request-shape differences**: subtle differences in request headers or body compared to active browser interaction.
- **Response-cookie/challenge dynamics**: provider attempting to issue a challenge cookie via `Set-Cookie` on 429.
- **Provider/WAF bot protection**: automated request classification by provider infrastructure.

No speculative fix (such as retrying automatically on error, altering headers, or modifying persistence) is
justified without empirical evidence.

### Sanitized diagnostic model (PR #20)

To resolve the observability gap without disclosing secrets or altering runtime behavior, PR #20 introduces
finite, structured rate-limit diagnostics:

1. **Finite operation classification**:
   - `operation`: strictly finite `RateLimitedOperation` enum (`GetPlanLekcjiContext` or `OTHER`).
   - Unrecognized or arbitrary operation strings are safely mapped to `OTHER` and never rendered,
     preventing log injection or secret leakage.

2. **`Retry-After` finite representation**:
   - `ABSENT`: header missing or blank.
   - `DELTA_SECONDS`: valid integer seconds (enforcing non-null, non-negative duration).
   - `HTTP_DATE`: valid RFC-1123 date parsed against injected clock (enforcing non-null, non-negative duration).
   - `MALFORMED`: negative delta, invalid date, or unparseable text.
   - Raw header text is never retained or rendered in `toString()` or logs.

3. **Response metadata**:
   - `contentFamily`: `JSON`, `HTML`, `OTHER`.
   - `setCookieCount`: bounded representation (`ZERO`, `ONE`, `TWO`, `THREE_PLUS`).
   - Standard rate-limit header presence: all six individual booleans are retained and logged:
     `rateLimitLimitPresent`, `rateLimitRemainingPresent`, `rateLimitResetPresent`,
     `xRateLimitLimitPresent`, `xRateLimitRemainingPresent`, `xRateLimitResetPresent`.
     Raw header values are never logged.

4. **Request shape observation**:
   - `method`: `POST`, `GET`, `OTHER`.
   - `contentType`: `FORM_URLENCODED`, `JSON`, `OTHER`, `NONE`.
   - Header presence booleans: `originPresent`, `refererPresent`, `verificationTokenPresent`, `appGuidPresent`,
     `xRequestedWithPresent`.
   - `cookiePresent` was removed after loopback verification confirmed that Spring's `HttpRequest.getHeaders()`
     does not expose cookies attached by the JDK `HttpClient`'s `CookieHandler`; `sessionCookiesBefore` provides
     the safe and accurate diagnostic instead.

5. **In-memory session cookie mutation**:
   - Snapshot taken immediately before and after request execution, strictly scoped to schedule requests
     (`postScheduleForm`). Bootstrap (`GetCache`) and journal tree (`GetTree`) requests do not take session snapshots.
   - `sessionCookiesBefore` / `sessionCookiesAfter`: finite bucket (`ZERO`, `ONE`, ..., `SEVEN`, `EIGHT_PLUS`).
   - `cookieMaterialChanged`: boolean indicating whether in-memory cookie store was mutated by response headers.
   - Bounded delta counts: `cookieIdentityAddedCount`, `cookieIdentityRemovedCount`, `cookieValueChangedCount`.
   - Secret cookie names, paths, domains, and values remain strictly confined within memory and are never rendered.
   - Even if in-memory session mutation occurs on 429, failed session material is **never persisted**
     (`sessions.replace(...)` is never called on failure).

6. **Resilience policy categorization**:
   - `delaySource`: `HEADER` or `FALLBACK`.
   - `delayBucket`: `ZERO`, `LE_10_SECONDS`, `LE_30_SECONDS`, `LE_60_SECONDS`, `LE_5_MINUTES`, `GT_5_MINUTES`,
     classified using exact `Duration` comparisons without subsecond truncation so bucket and inline/deferred
     decision never disagree.
   - `decision`: `INLINE_RETRY` or `DEFERRED_GATE`.

7. **Single structured log line per 429 attempt**:
   - Exactly ONE sanitized line emitted at WARN level per individual HTTP 429 attempt.
   - If inline retry occurs, each real 429 response generates exactly one line.
   - Never logs URLs, tokens, cookie values, exception stack traces, or form bodies.
   - Example line:
     `VULCAN schedule HTTP 429: operation=GetPlanLekcjiContext status=429 content=JSON retryAfter=DELTA_SECONDS delaySource=HEADER delayBucket=LE_30_SECONDS decision=DEFERRED_GATE setCookie=ZERO sessionCookiesBefore=TWO sessionCookiesAfter=TWO cookieMaterialChanged=false cookieAdded=0 cookieRemoved=0 cookieValueChanged=0 method=POST contentType=FORM_URLENCODED originPresent=true refererPresent=true tokenPresent=true appGuidPresent=true xRequestedWithPresent=true rateLimitLimitPresent=false rateLimitRemainingPresent=false rateLimitResetPresent=false xRateLimitLimitPresent=false xRateLimitRemainingPresent=false xRateLimitResetPresent=false attempt=1/3`
   - Orchestration layer remains decoupled from HTTP transport internals.

## 2026-09-10 fresh session discrimination test and stale-session recovery (PR #21)

### Empirical discrimination evidence

Following deployment of PR #20 sanitized diagnostics on `acer-server`, an instrumented monitoring cycle reproduced the exact schedule HTTP 429 response on the first weekly scope:

```
VULCAN schedule rate limited: operation=GetPlanLekcjiContext status=429 content=HTML retryAfter=ABSENT delaySource=FALLBACK delayBucket=LE_30_SECONDS decision=DEFERRED_GATE setCookie=ONE sessionCookiesBefore=SIX sessionCookiesAfter=SIX cookieMaterialChanged=false cookieAdded=0 cookieRemoved=0 cookieValueChanged=0 method=POST contentType=FORM_URLENCODED originPresent=true refererPresent=true tokenPresent=true appGuidPresent=true xRequestedWithPresent=true rateLimitLimitPresent=false rateLimitRemainingPresent=false rateLimitResetPresent=false xRateLimitLimitPresent=false xRateLimitRemainingPresent=false xRateLimitResetPresent=false attempt=1/3
```

Key observations from this production test:
- The request method was `POST`, content type was `application/x-www-form-urlencoded`, and all five required headers (`Origin`, `Referer`, `X-V-RequestVerificationToken`, `X-V-AppGuid`, `X-Requested-With`) were present.
- The response was HTTP 429 with `content=HTML`.
- `Retry-After` was completely `ABSENT` (no seconds delta, no HTTP date).
- All standard `RateLimit-*` and `X-RateLimit-*` headers were absent.
- Exactly one `Set-Cookie` header was returned, but in-memory cookie material was unchanged (`cookieMaterialChanged=false`).

Immediately following this failure, a controlled fresh session discrimination test was performed:
1. Re-authentication via human `/connect` flow established a brand-new session for the account.
2. Scheduler cycle #1 immediately succeeded on both weekly scopes (CURRENT and NEXT): `successes=2, failures=0, stoppedEarly=false`. Baseline notifications were dispatched cleanly to Telegram.
3. Scheduler cycle #2 five minutes later again completed with 2/2 successes: `successes=2, failures=0, stoppedEarly=false`.

The fresh-session discrimination test strongly indicates that the previously persisted session was stale or otherwise no longer accepted by VULCAN: the identical server, egress IP, tenant, and request format succeeded immediately with a fresh session, whereas an older persisted session was rejected with an HTTP 429 HTML page.

### Stale session classification and bounded recovery

PR #21 introduces a strict, finite classifier (`Schedule429Classifier`) for schedule HTTP 429 responses:

1. **Strict Stale Signature**:
   An observation is classified as `STALE_SESSION` if and only if ALL of the following hold:
   - Observation is present
   - `operation == RateLimitedOperation.GET_PLAN_LEKCJI_CONTEXT`
   - `statusCode == 429`
   - `contentFamily == ContentFamily.HTML`
   - `retryAfterRepresentation == RetryAfterRepresentation.ABSENT`
   - No rate-limit headers present (`!rateLimitHeaders.anyPresent()`)
   - Request shape: `POST`, `FORM_URLENCODED`, and `originPresent`, `refererPresent`, `verificationTokenPresent`, `appGuidPresent`, `xRequestedWithPresent` all true.

2. **Conservative Defaults**:
   All non-matching 429 responses (JSON responses, HTML with `Retry-After`, HTML with rate-limit headers, non-schedule operations, or incomplete request shapes) remain `RATE_LIMITED` and proceed through ordinary backoff/deferral.
   Cookie counts, mutations, and challenge cookies are deliberately excluded from classifier inputs.

3. **Routing to Authentication Recovery**:
   When `STALE_SESSION` is classified in `ResilientWeeklyScheduleSource`:
   - Single sanitized log line is emitted: `delaySource=NONE delayBucket=ZERO decision=AUTHENTICATION_REQUIRED`.
   - `RateLimitBackoffGate` is NOT extended.
   - Zero inline delay or retry occurs.
   - Throws `ScheduleSourceException.of(SourceFailureKind.AUTHENTICATION_REQUIRED)`.
   - `RecoveringAccountWeeklyScheduleSource` catches this and delegates to `VulcanSessionManager.recover(accountId)`:
     - **Without remembered credentials**: Immediately marks account `RECONNECT_REQUIRED` in database; zero retries, zero browser auth attempts.
     - **With remembered credentials**: Uses bounded recovery budget (at most 1 recovery per cycle). If recovery succeeds, retries schedule fetch once; if recovery or subsequent fetch fails, transitions to `RECONNECT_REQUIRED`.

4. **Target Suppression on `RECONNECT_REQUIRED`**:
   `MonitoringSubscriptionRepository.findDistinctActiveTargets()` filters on `account.status = 'CONNECTED'`. When an account transitions to `RECONNECT_REQUIRED`, its targets are automatically suppressed from future scheduler cycles while user subscriptions remain enabled (`enabled=TRUE`). Upon subsequent successful re-authentication, targets are restored without data loss.

### Corrected `authenticated_at` timestamp semantics

Previously, routine session rotation after each successful schedule request called `persistence.replaceRecovered(...)`, which invoked `account.connected(...)` and incorrectly advanced `vulcan_account.authenticated_at` on routine schedule fetches. Additionally, it unnecessarily loaded and decrypted remembered credentials during routine rotations.

PR #21 corrects this architectural seam:
- `VulcanAccountSecretEntity` adds `replaceSession(...)`, updating session ciphertext/nonce and `updated_at` while preserving `credential_nonce` and `credential_ciphertext`.
- `VulcanSecretStore` and `EncryptedVulcanSecretStore` add `replaceSession(...)`, encrypting only session material and never loading credentials.
- `VulcanRecoveryPersistence` adds `rotateSession(...)`, persisting rotated session material without touching `VulcanAccountEntity`.
- `VulcanSessionManager.replace(...)` calls `rotateSession(...)` directly without loading or decrypting credentials.
- `authenticated_at` now strictly reflects genuine authentication events: human `/connect` completion (`VulcanConnectionCompletion.complete`) and automatic remembered-credential recovery (`VulcanRecoveryPersistence.replaceRecovered`).
