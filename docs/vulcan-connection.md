# Secure VULCAN Connection

## Scope and phase boundary

Phase 7 connects one authorized VULCAN account to one internal application user. It persists an encrypted HTTP session and an account-scoped class catalog. It does **not** use that account for scheduled monitoring, change tracking, notification identity, or automatic subscriptions. Journal IDs cannot be assumed globally unique across accounts. Phase 8 must introduce account/catalog-aware identities and Telegram class selection before monitoring is wired to connected accounts.

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

Every attempt owns fresh Playwright, Browser, BrowserContext, and Page instances. Persistent profiles, storage-state files, screenshots, video, traces, HAR, DOM dumps, and provider response text are not produced. Resources close on success and every failure path.

## Encryption and persistence

`VulcanSessionMaterial`, remembered credentials, the configured master-key holder, and browser observations are normal classes with redacted `toString()` implementations. The explicit binary payload codec uses distinct versioned magic values and length-delimited UTF-8 fields; Java serialization and XML are not used.

AES/GCM/NoPadding uses a 256-bit key, a fresh random 12-byte nonce per encryption, a 128-bit tag, and AAD containing account ID, payload purpose, and format version. Session and remembered-credential payloads are encrypted separately. A wrong key, nonce, AAD, tag, or corrupted ciphertext fails closed. Ciphertext is never deleted or replaced automatically after a decryption failure.

When remember credentials is false, credential nonce and ciphertext remain null. When true, portal URI, login, and password are stored only inside the separate encrypted credential payload. The portal URI and login are not plaintext account columns.

## Catalog and recovery foundation

After complete verification, discovered journals are inserted or updated and marked active. Previously known journals absent from that complete tree become inactive rather than being deleted. Failed authentication or discovery cannot deactivate catalog rows because synchronization occurs only in the final transaction after successful verification. Active reads are deterministic and scoped through the internal user/account association, so the same journal ID can exist for different accounts.

`VulcanSessionManager` can load and reconstruct a stored session, persist a cookie-rotation snapshot, and perform an explicit recovery when remembered credentials exist. Connect and explicit recovery both persist the verifier's post-`getCache()`/`getTree()` snapshot rather than the pre-verification browser capture. Without remembered credentials it marks the account reconnect-required. Nothing calls this manager from `MonitoringCycleRunner`, and there are no background Playwright jobs in Phase 7.

## Manual validation

CI tests the security and orchestration boundaries with synthetic sessions, a fake browser authenticator, MockMvc, AES-GCM tests, and PostgreSQL/Testcontainers. It never performs a VULCAN network request and never requires Chromium. Real tenant login details can vary and must be validated manually with an authorized test account. Do not record or publish credentials, URLs, request headers, cookies, screenshots, traces, class data, or browser state during that validation.
