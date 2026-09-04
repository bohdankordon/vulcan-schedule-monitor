# Unofficial VULCAN Protocol Notes

## Scope and limitations

This document describes unofficial, read-only behavior derived from browser network observations. It is not documentation for an official VULCAN API, and this project is not affiliated with or endorsed by VULCAN sp. z o.o. Endpoints, fields, and payload behavior may change at any time.

The implementation should minimize traffic and collect only data needed for the user's requested monitoring. Authentication secrets, cookies, session data, tenant identifiers, personal data, HAR captures, and raw production responses must remain outside the repository. Examples and tests must use synthetic data.

The project is intended only for accounts and resources the user is authorized to access. Integrations must respect authentication and authorization controls and must not bypass or circumvent access restrictions.

Observed browser behavior uses week-sized date ranges for schedule requests. Longer ranges have not been verified and must not be assumed to work.

## Observed endpoint responsibilities

### `DziennikCache.mvc/GetCache`

Observed as a bootstrap/configuration request that supplies lesson-period definitions and configuration related to the current school year.

**Implemented now:** the read-only adapter supplies the observed cache-buster, validates the response envelope, and maps the current school year plus lesson period `Id`, `Numer`, `Poczatek`, and `Koniec` fields. The synthetic legacy date portion of the time values is discarded.

`Numer` is not required to be positive: `0` is an observed valid lesson number for a pre-first-lesson slot. `IdPoraLekcji` remains an opaque external identifier and must be resolved through returned lesson-period data; `Numer` must never be calculated arithmetically from this identifier.

### `Dziennik.mvc/GetTree`

Observed as discovery of journals and classes available to the authenticated account. Relevant response concepts include:

- `IdDziennik` and `IdOddzial`;
- class name and grade level;
- school year;
- whether a journal exists; and
- school or unit abbreviation.

`IdDziennik` must be discovered for the current account context and must never be hard-coded.

The observed response is the tree object itself, with `children` at its direct root; this endpoint is not treated as a generic `success`/`data` envelope. Its `zadanaData` request parameter contains the current local date and time rather than local midnight.

**Implemented now:** recursive traversal starts at that direct root and handles objects, dictionary-like children, and arrays. Only nodes explicitly reporting an existing journal are mapped into the protocol-independent class model. The cache-buster and Warsaw-local `zadanaData` timestamp come from the same injected clock.

### `PlanLekcji.mvc/GetContext`

Observed as a full schedule context containing schedule entries, changed or effective entries, and lookup/reference data. Its intended future role is bootstrap or full-context synchronization, not frequent polling.

### `PlanLekcji.mvc/GetPlanLekcjiContext`

Observed as a lighter schedule response containing the base schedule, schedule with changes, and days off. Its intended future role is regular schedule monitoring using the week-sized ranges observed in the browser.

**Implemented now:** callers provide a journal discovered from `GetTree` and a date within the requested week. The adapter derives Monday and Sunday boundaries, sends all date form values at local midnight, and preserves the supplied date as the browser-style `data` anchor inside that week. The monitoring adapter verifies that the returned journal and week match the requested scope; failures are never converted to empty snapshots.

### `Home.mvc/RefreshSession`

Observed as session keep-alive or touch behavior. It must not be modeled or described as an OAuth refresh-token mechanism.

## Schedule-change concepts

Observed responses distinguish a base schedule from an effective schedule that incorporates changes. Relevant conceptual fields include:

- `CzyZmiana`, indicating change-related presentation or state;
- `IdPozycjiPlanu`, which can correlate an effective entry with a base-plan entry within the response;
- `ChangeAnnotation`, an array containing zero or more human-readable annotation strings;
- `Bolded`, a presentation cue; and
- `Striked`, a presentation cue for replaced or inactive information.

Substitution annotations may describe that one teacher or lesson replaces another. Documentation, fixtures, and tests must use synthetic people and codes rather than values captured from a school.

External identifiers are protocol-level values. No internal VULCAN identifier should be assumed to be a stable business identifier across weeks without additional evidence and an explicit design decision.

**Implemented now:** base `Id` and effective `IdPozycjiPlanu` values are used only inside the adapter for correlation within one response. Multiple effective rows are retained. Empty, single-entry, and multiple-entry `ChangeAnnotation` arrays are supported, and every non-empty annotation is considered. A recognized synthetic `zastępstwo: [TEACHER_CODE], subject_code` annotation becomes a teacher-substitution change; unknown annotations and marker-only changes become explicit unknown changes rather than being discarded.

Each extracted change retains protocol-independent planned and effective lesson occurrences when available. This context distinguishes group or subject occurrences that share a date and lesson period without exposing the response's correlation row IDs. Unparsed annotations are retained transiently for investigation through an explicit accessor, but their raw content is redacted from diagnostics and must not automatically be logged or persisted.

## Intended integration posture

The integration keeps browser-observed fields at the system boundary and translates them into internal models. Full context retrieval and lighter monitoring retrieval have different observed responsibilities and should be used accordingly. The current implementation fails safely when an expected envelope or mapped field changes and does not place response bodies in exceptions.

HTTP failures are sanitized into authentication required (`401`/`403`), rate limited (`429`), server error (`5xx`), permanent client error (other `4xx`), transport error, and session redirect categories. Automatic redirect following remains disabled. A `2xx` response explicitly identified as HTML is treated as a session/authentication failure before JSON decoding; its body is neither stored nor included in diagnostics. `Retry-After` supports delta-seconds and RFC 1123 HTTP dates using an injected clock. The raw header value is not retained in errors.

Phase 7's Playwright adapter observes authenticated same-origin VULCAN requests and accepts a session only when a request carries both `X-V-RequestVerificationToken` and `X-V-AppGuid`. It derives the authenticated application path from that request, validates the Referer inside the same path, and captures all cookies returned by the browser for that application URL without a cookie-name allowlist. A newly captured session must successfully parse both `GetCache` and the complete `GetTree` for the returned school year before any account, session, or catalog update commits.

Public-flow inspection in September 2026 found the teacher/employee direct-login link on the tenant landing page and semantic username/current-password fields on a VULCAN-hosted form. The adapter uses those identifiable form semantics only. Any non-VULCAN redirect, MFA field, CAPTCHA surface, unsupported identity flow, or absence of authenticated protocol headers fails with a sanitized category. It never submits a password outside an allowlisted HTTPS `vulcan.net.pl` host.

`PlanLekcji.mvc/GetContext` and `Home.mvc/RefreshSession` remain documented observations only. Schedule polling already creates authenticated traffic, so `RefreshSession` has not been added without a concrete tested need and is not modeled as OAuth refresh. Account-aware monitoring loads the selected account's encrypted session for each weekly request and persists ordinary response-cookie rotation; authentication failures may invoke the same verified Playwright boundary once when remembered credentials exist.
