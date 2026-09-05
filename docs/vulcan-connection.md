# Secure VULCAN Connection

## Scope and monitoring boundary

The connection flow binds one authorized VULCAN account to one internal application user, persists an encrypted HTTP session, and maintains an account-scoped class catalog. Scheduled monitoring now uses that persisted session through the selected catalog row. Journal IDs remain protocol-local and are never treated as globally unique: the runtime carries account ID for session selection, catalog ID for durable class identity, and journal ID only for VULCAN requests.

This phase does not add multiple accounts per user, account switching/disconnect UX, or speculative login variants. Those semantics remain deliberately outside the current one-account-per-user product.

## User journey

1. The user sends `/connect` in a registered private Telegram chat.
2. When enabled, the bot creates 32 cryptographically random bytes, stores only their SHA-256 hash, and returns a link rooted at the configured public base URL.
3. `GET /connect/{token}` validates the capability, places it in a short-lived `HttpOnly`, `SameSite=Strict`, path-limited cookie, and responds with a `303` redirect to `/connect`. Production cookies are `Secure`.
4. The self-contained page asks only for the VULCAN portal URL, login, password, and an opt-in remember-credentials checkbox. The checkbox defaults to false. Spring Security CSRF remains enabled.
5. The portal URL is validated before Playwright starts. Only standard-port HTTPS hosts equal to `vulcan.net.pl` or ending in `.vulcan.net.pl`, without userinfo or fragments, are accepted.
6. Playwright performs the supported direct login in a new headless Chromium browser context. Credentials are never accepted in Telegram.
7. Browser request observation retains only the request URI, Referer, request-verification token, and AppGuid required to identify an authenticated application request. It does not retain an arbitrary header map. Cookies are obtained separately for the authenticated application URL, and cookie names are not hard-coded.
8. A reconstructed `VulcanClient` must successfully parse `getCache()` and a complete `getTree(currentSchoolYear)`. The verified result contains the final session snapshot taken after both calls, including any `Set-Cookie` rotations received during verification.
9. A short transaction locks and revalidates the token, upserts the account, encrypts the post-verification session snapshot, synchronizes the complete catalog, and consumes the token atomically. Browser and protocol work never run inside this transaction.

## Feature configuration

The feature is disabled by default:

```yaml
vulcan:
  connection:
    enabled: false
    public-base-url: ""
    token-ttl: PT10M
    max-credential-attempts: 5
    master-key: ${VULCAN_MASTER_KEY:}
    playwright-headless: true
```

Disabled startup creates no controller, authenticator, encryption store, connection service, or session manager; it requires no master key or browser. `/connect` returns an operator-disabled message. When enabled, the public base URL must be HTTPS. HTTP is accepted only for explicit localhost addresses for tests or local development. Connect links are always built from this configuration, never from the request `Host` header.

`VULCAN_MASTER_KEY` must be standard Base64 encoding of exactly 32 random bytes. A suitable key can be generated outside the repository with a trusted cryptographic tool. Do not place the generated value in YAML, source code, logs, tickets, shell history, or commits. A missing or malformed key fails enabled startup with a sanitized error.

Playwright is a compile-time dependency, but Chromium is never downloaded or launched by Maven tests or default startup. Install it manually on an authorized runtime host:

```powershell
.\mvnw.cmd -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install chromium" exec:java
```

## Web security

Only `/connect` and `/connect/**` are publicly permitted; the token is the narrow authorization capability. Other web requests are denied. Connection responses use `Cache-Control: no-store`, `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`, frame denial, and a CSP that denies all default sources and permits only the self-posting form plus inline local styling. There is no external JavaScript, CDN, font, image, analytics, or third-party request.

Invalid or expired tokens never render the credential form and clear the cookie. Successful connection and exhaustion of the credential-attempt budget also clear it. Invalid credentials consume one attempt but leave the token usable while budget remains. Transient network/provider failures, protocol incompatibility, MFA, CAPTCHA, and unsupported flows do not count as bad-password attempts.

## Direct-login boundary

Public inspection identified a tenant landing page with a teacher/employee `LoginEndpoint.aspx` link and a VULCAN-hosted direct login form using semantic username and current-password autocomplete fields. The adapter supports only this identifiable path. Before either credential is entered, it requires the username, password, and selected submit control to belong to the same form. It reads the browser-resolved effective action, including relative/empty actions and a submitter `formaction` override, and requires the effective method, including `formmethod`, to be `POST`. Both the current page and effective submission target must be allowlisted HTTPS VULCAN URLs. The policy is checked again immediately before the verified in-form submitter is clicked.

Once credential entry begins, a temporary Playwright request route aborts requests to every non-allowlisted destination before network transmission without inspecting a request body or recording a destination URL. Off-domain identity providers, external form actions, MFA, CAPTCHA, interactive verification, missing form semantics, or a session that never produces the required authenticated request values fail with a sanitized category. The implementation does not bypass or weaken any security control.

Before credential entry and before installing that request guard, the authenticator checks the landing page and discovered login page for the known ordinary VULCAN privacy screen. It may appear in the top-level document or inside a visible VULCAN iframe. Each frame's current URL and every ancestor's URL must pass the runtime VULCAN allowlist before frame DOM inspection. Detached, hidden, external, HTTP, malformed, and opaque-URL frames are excluded from DOM inspection; an allowed child beneath an untrusted ancestor is also excluded. External frame content is never inspected. Frame consent does not broaden the credential destination boundary.

One shared policy matches the visible `Szanujemy Twoją prywatność` heading, its nearest semantic dialog (or container shared with the known settings action), and exactly one whitespace/NBSP-normalized `Zgadzam się` action. Native buttons, button/submit inputs, role buttons, and ordinary exact text elements remain supported. Settings, hyperlinks, inert/unsafe actions, and credential-bearing consent forms are rejected. Safe consent form actions are compared against the actual document URI: the frame URL for iframe consent. No credentials are entered or authentication forms submitted in frames.

Consent discovery uses a two-second Playwright condition window before concluding that no known privacy surface exists. Each condition evaluation enumerates the current frames and rechecks URI/ancestor trust, handling late attachment, initially empty/opaque frame URLs that later commit to VULCAN, and delayed heading/action rendering. Opaque frames are observed only through browser lifecycle metadata until eligible. There is no arbitrary sleep or early exit based on a momentarily actionable login link. Ready consent proceeds immediately; at the deadline a final scan permits absence but rejects any still-unresolved known privacy context. Multiple known surfaces, ambiguous/unsafe actions, and observed loss of trust fail closed at `COOKIE_CONSENT`. Navigation listeners and retained owner handles are always released.

The existing click and dismissal bounds remain three seconds each. Top-level dismissal requires the original container to disappear. Frame dismissal requires detachment or a hidden enclosing iframe owner, including a zero-sized owner, so an empty but visible iframe cannot continue intercepting login clicks. Normal detachment after clicking succeeds without stale DOM inspection. Frame and ancestor trust are rechecked during handling, and observed unsafe navigation cannot be erased by subsequent detachment. No force-click, CSS manipulation, or DOM-removal bypass is used.

After consent dismissal, direct login uses an ordinary click on the original Page followed by `DOMContentLoaded`, since the new document can still be loading when the click returns. Both navigation bounds remain 30 seconds. No popup/new Page handling is needed. MFA and CAPTCHA are never automated.

Submitted portal URLs still reject fragments. Runtime page, form-target, and request validation permits query strings and fragments because they do not change the network destination. The boundary remains HTTPS, `vulcan.net.pl` or its subdomains, default port or 443, and no userinfo. Visible CAPTCHA and one-time-code controls are rejected; hidden or inert matching elements do not count as interactive challenges or hide later visible matches.

Browser-auth failures produce one sanitized warning, for example `VULCAN browser authentication failed: stage=POST_LOGIN_VALIDATION category=UNSUPPORTED_AUTH_FLOW`. Stages are `INITIAL_NAVIGATION`, `DIRECT_LOGIN_DISCOVERY`, `DIRECT_LOGIN_NAVIGATION`, `COOKIE_CONSENT`, `LOGIN_FORM_VALIDATION`, `CREDENTIAL_SUBMISSION`, `POST_LOGIN_VALIDATION`, and `SESSION_CAPTURE`. Logs include only finite stage/category values, never exception details, URLs, credentials, or session metadata. An unsupported post-login page does not itself establish that MFA or CAPTCHA occurred; the category distinguishes those challenges from unsafe navigation. Missing complete session material remains a protocol failure (or invalid credentials when the password form is still visible), and persistence still requires full protocol verification.

Separate follow-up: the current `TRANSIENT` UI wording can imply VULCAN is unavailable even when a local Playwright interaction timed out. This consent fix does not change that presentation; such a timeout is not evidence of a provider outage.

Every attempt owns fresh Playwright, Browser, BrowserContext, and Page instances. Persistent profiles, storage-state files, screenshots, video, traces, HAR, DOM dumps, and provider response text are not produced. Resources close on success and every failure path.

## Encryption and persistence

`VulcanSessionMaterial`, remembered credentials, the configured master-key holder, and browser observations are normal classes with redacted `toString()` implementations. The explicit binary payload codec uses distinct versioned magic values and length-delimited UTF-8 fields; Java serialization and XML are not used.

AES/GCM/NoPadding uses a 256-bit key, a fresh random 12-byte nonce per encryption, a 128-bit tag, and AAD containing account ID, payload purpose, and format version. Session and remembered-credential payloads are encrypted separately. A wrong key, nonce, AAD, tag, or corrupted ciphertext fails closed. Ciphertext is never deleted or replaced automatically after a decryption failure.

When remember credentials is false, credential nonce and ciphertext remain null. When true, portal URI, login, and password are stored only inside the separate encrypted credential payload. The portal URI and login are not plaintext account columns.

## Catalog, session rotation, and automatic recovery

After complete verification, discovered journals are inserted or updated and marked active. Previously known journals absent from that complete tree become inactive rather than being deleted. Failed authentication or discovery cannot deactivate catalog rows because synchronization occurs only in the final transaction after successful verification. Active reads are deterministic and scoped through the internal user/account association, so the same journal ID can exist for different accounts.

`VulcanSessionManager` loads and reconstructs the session for a specific account and encrypts every successful weekly request's post-response cookie snapshot. A failure to persist this rotation prevents the fetched schedule from reaching reconciliation.

Ordinary weekly transport/server retries run inside the authentication-recovery layer and never reset its budget. When a weekly operation reports authentication required, a session redirect, or unexpected HTML, one logical scope execution requests at most one account-scoped recovery. Recovery is serialized per account. With remembered credentials it reuses the same Playwright authenticator, verifies `getCache()` and complete `getTree()`, persists the verifier's post-verification session, and retries the weekly operation through ordinary request resilience. A successful retry also persists its cookie rotation. Authentication failure after recovery marks the account reconnect-required without entering recovery again. There is no recursive recovery loop, and Playwright is not started for an ordinary successful request.

Without remembered credentials, or for invalid credentials, MFA, CAPTCHA, unsupported authentication, or protocol authentication failure, the account becomes `RECONNECT_REQUIRED` and is excluded from future target queries. Transient recovery failure does not destructively reset account state; it blocks that account's remaining scopes only for the current cycle, allowing a later cycle to try once again while other accounts continue. Reconnection state is visible through `/status`, `/classes`, and `/connect`; the scheduler does not bypass the durable notification model with a direct Telegram alert.

## Manual validation

An authorized real normal Telegram `/connect` validation completed successfully with 26 discovered classes and Remember credentials disabled. `/status` reported VULCAN connected with zero monitored classes, and `/classes` displayed the persisted catalog with pagination. This validates the token/controller, verified encrypted session/account completion, catalog persistence, and Telegram catalog read path. Monitoring validation is separate.

### Opt-in local real-VULCAN smoke harness (developers only)

Use only an account you are authorized to test. **`-Run` makes real VULCAN requests: one invocation is one connection attempt.** Configure manually in Windows PowerShell 7 with Java 21 available and Playwright Chromium already installed:

```powershell
.\scripts\vulcan-real-smoke.ps1 -Configure
```

The prompts collect the portal URL, login, and password; login and password use secure input. A versioned `VSM1` binary payload contains three length-prefixed UTF-8 fields and is protected with Windows DPAPI `DataProtectionScope.CurrentUser`. Only Base64 ciphertext is written to `.dev/vulcan-real-smoke.dpapi`; no plaintext intermediate file is created. This bundle is separate from the application master key and Telegram token. **DPAPI CurrentUser is development convenience and does not isolate credentials from arbitrary code running as the same Windows user. Never commit or share `.dev` contents, or supply credentials through an agent prompt.**

After explicitly authorizing one attempt:

```powershell
.\scripts\vulcan-real-smoke.ps1 -Run
```

The script first compiles the test-source diagnostic main and resolves its existing Maven classpath without credentials. It then decrypts only the smoke bundle in memory and sends the binary payload to Java's redirected stdin. Credentials are never command-line arguments, environment variables, build inputs, or saved browser state. Transient byte/char arrays and BSTR buffers are cleared where practical; immutable JVM/.NET strings and browser-process memory cannot be reliably wiped. The child inherits only a small runtime environment allowlist, with monitoring and Telegram explicitly disabled. Third-party stdout/stderr are suppressed; the parent displays only a strictly validated finite diagnostic protocol.

The main is opt-in, excluded from the production jar, and starts no Spring context, database, scheduler, token controller, recovery loop, or Telegram component. It reuses `PortalUrlValidator`, the default-headless `PlaywrightVulcanBrowserAuthenticator` (including `VulcanSessionCapture`), `VulcanSession.fromMaterial`, `DefaultVulcanSessionVerifier`, and `VulcanClient.getCache/getTree` with the real transport/adapters. It stops after verified session reconstruction/snapshot and production class conversion. No account, session, catalog row, or password is persisted; no remembered-credential slot is touched. One browser authentication and one verifier invocation occur, without harness retries. Each child has a five-minute process deadline; timeout/cancellation closes its process tree. Chromium is never installed by this runner.

Diagnostics report these finite stages: `PORTAL_VALIDATION`, `BROWSER_AUTH`, `SESSION_CAPTURE`, `SESSION_MATERIAL_RECONSTRUCTION`, `VERIFY_CACHE_REQUEST`, `VERIFY_CACHE_PARSE`, `VERIFY_SCHOOL_YEAR`, `VERIFY_TREE_REQUEST`, `VERIFY_TREE_PARSE`, `SESSION_SNAPSHOT`, and `VERIFIED`. A stage can be `PASS`, `FAIL`, `INCOMPLETE`, or `NOT_REACHED`; `PASS` for an intermediate stage is not overall authentication success. The two parse stages cover JSON decoding and schema/domain conversion; school-year extraction has its own stage. Optional HTTP facts contain only status family, content family, and a redirect boolean. The smoke-only category `SESSION_AUTHENTICATION` distinguishes redirects, 401/403, and unexpected HTML from parser failures without exposing destinations or bodies. Normal production constructors use a no-op observer and retain existing public error mapping/logging.

Cache lesson-period failures can additionally report a fixed `cacheFailure` enum: periods/ID/number/start/end schema, start/end time format, number range, or duplicate period ID. A rejected start/end value that parses as an ISO local time receives the more specific `PERIOD_START_TIME_ONLY` / `PERIOD_END_TIME_ONLY` label. This is diagnostic classification only: it does not accept a new time representation or change the protocol failure. No field values, IDs, or response text are included. The PowerShell report allowlist rejects unknown enum values and duplicate diagnostic fields before displaying any output.

GetCache lesson-period start/end timestamps accept exactly the legacy `yyyy-MM-dd HH:mm:ss` shape and the existing `yyyy-MM-dd'T'HH:mm:ss` shape after the shared text-field trimming. Parsing validates the complete date and time strictly with a fixed locale before extracting the local time. Time-only values, fractions, offsets, other separators, malformed dates, and embedded timestamp fragments are not accepted.

For example, a synthetic parse failure could end with:

```text
stage.VERIFY_CACHE_REQUEST=PASS
stage.VERIFY_CACHE_PARSE=FAIL
http.VERIFY_CACHE_REQUEST=SUCCESS,JSON,false
category=PROTOCOL_FAILURE
result=FAIL
```

`SUCCESS` in HTTP metadata means 2xx; other status families are `INFORMATIONAL`, `REDIRECT`, `CLIENT_ERROR`, `SERVER_ERROR`, and `OTHER`. Content is `JSON`, `HTML`, or `OTHER`. Successful verification also reports only `classCount`, never names. Exit codes are 0 for successful verification, 1 for a connection failure, and 2 for harness/input/setup failure. No raw exceptions, URLs, paths, headers, cookies, tokens, names, HTML/JSON, screenshots, HAR, traces, video, or storage state are emitted or persisted. MFA, CAPTCHA, and unsupported authentication stop the attempt; there is no bypass.

Remove only this bundle, or view side-effect-free help:

```powershell
.\scripts\vulcan-real-smoke.ps1 -Clear
.\scripts\vulcan-real-smoke.ps1 -Help
```

Normal Maven verification never invokes real mode or contacts VULCAN. Tests use mocked browsers, loopback synthetic HTTP, and an isolated temporary DPAPI bundle on Windows (the DPAPI test is skipped on non-Windows CI). No real credentials or existing `.dev` secrets are needed. For future provider compatibility changes, core smoke and normal Telegram `/connect` validation are complementary: the harness does not exercise the token/controller or persistence/catalog completion path.

CI tests the security and orchestration boundaries with synthetic encrypted sessions, a fake browser authenticator, local WireMock VULCAN responses, MockMvc, AES-GCM tests, and PostgreSQL/Testcontainers. It never contacts a real VULCAN tenant and never requires Chromium. Real tenant login details can vary and must be validated manually with an authorized test account. Do not record or publish credentials, URLs, request headers, cookies, screenshots, traces, class data, or browser state during that validation.
