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

`IdPoraLekcji` is an opaque external identifier. It must be resolved through returned lesson-period data; a lesson number must never be calculated arithmetically from this identifier.

### `Dziennik.mvc/GetTree`

Observed as discovery of journals and classes available to the authenticated account. Relevant response concepts include:

- `IdDziennik` and `IdOddzial`;
- class name and grade level;
- school year;
- whether a journal exists; and
- school or unit abbreviation.

`IdDziennik` must be discovered for the current account context and must never be hard-coded.

**Implemented now:** recursive tree traversal handles objects, dictionary-like children, and arrays. Only nodes explicitly reporting an existing journal are mapped into the protocol-independent class model.

### `PlanLekcji.mvc/GetContext`

Observed as a full schedule context containing schedule entries, changed or effective entries, and lookup/reference data. Its intended future role is bootstrap or full-context synchronization, not frequent polling.

### `PlanLekcji.mvc/GetPlanLekcjiContext`

Observed as a lighter schedule response containing the base schedule, schedule with changes, and days off. Its intended future role is regular schedule monitoring using the week-sized ranges observed in the browser.

**Implemented now:** callers provide a journal discovered from `GetTree` and a date within the requested week. The adapter derives Monday and Sunday boundaries, sends all date form values at local midnight, and preserves the supplied date as the browser-style `data` anchor inside that week. This is response retrieval only, not scheduled polling.

### `Home.mvc/RefreshSession`

Observed as session keep-alive or touch behavior. It must not be modeled or described as an OAuth refresh-token mechanism.

## Schedule-change concepts

Observed responses distinguish a base schedule from an effective schedule that incorporates changes. Relevant conceptual fields include:

- `CzyZmiana`, indicating change-related presentation or state;
- `IdPozycjiPlanu`, which can correlate an effective entry with a base-plan entry within the response;
- `ChangeAnnotation`, carrying human-readable change context;
- `Bolded`, a presentation cue; and
- `Striked`, a presentation cue for replaced or inactive information.

Substitution annotations may describe that one teacher or lesson replaces another. Documentation, fixtures, and tests must use synthetic people and codes rather than values captured from a school.

External identifiers are protocol-level values. No internal VULCAN identifier should be assumed to be a stable business identifier across weeks without additional evidence and an explicit design decision.

**Implemented now:** base `Id` and effective `IdPozycjiPlanu` values are used only inside the adapter for correlation within one response. Multiple effective rows are retained. A recognized synthetic `zastępstwo: [TEACHER_CODE], subject_code` annotation becomes a teacher-substitution change; unknown annotations and marker-only changes become explicit unknown changes rather than being discarded.

## Intended integration posture

The integration keeps browser-observed fields at the system boundary and translates them into internal models. Full context retrieval and lighter monitoring retrieval have different observed responsibilities and should be used accordingly. The current implementation fails safely when an expected envelope or mapped field changes and does not place response bodies in exceptions.

`PlanLekcji.mvc/GetContext` and `Home.mvc/RefreshSession` remain documented observations only. Automated login/re-login, session touching, scheduled monitoring, cross-poll change detection, persistence, and notifications are still planned.
