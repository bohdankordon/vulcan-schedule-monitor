# Persistent change tracking

## Scope and baseline

Tracking is scoped to one journal and one Monday-to-Sunday week. The database prevents multiple scope rows for the same journal and week start.

The first successful snapshot establishes a baseline. Its current changes are persisted as active state, but it emits no `NEW` transitions. An empty successful snapshot also establishes the baseline. The result explicitly reports whether the baseline was established during that reconciliation and the number of active changes.

## Lifecycle reconciliation

After the baseline:

- a current key absent from active state produces `NEW`;
- a current key with the same fingerprint is unchanged and produces no transition;
- a current key with a different fingerprint produces `UPDATED`; and
- a previously active key absent from the current successful snapshot produces `RESOLVED`.

`firstSeenAt` is retained while a change remains active; `lastSeenAt` advances on every successful observation. A resolved row is removed from active state after its transition is produced. If the logical change later reappears, it is `NEW` with a new first-seen timestamp. This phase intentionally keeps active state rather than raw snapshots or a historical event ledger. Durable delivery history belongs to a future transactional notification outbox.

## Successful snapshots only

Resolution is inferred only by `reconcileSuccessfulSnapshot`, which accepts a non-null complete weekly `ScheduleSnapshot`. The orchestration boundary fetches a complete weekly snapshot first and calls the tracker only after that succeeds. A network, authorization, timeout, parsing, or other fetch failure therefore leaves active state untouched and cannot produce `RESOLVED`.

No scheduler is implemented in this phase.

## Semantic identity

Both identities are lowercase, 64-character SHA-256 hashes over an unambiguous length-prefixed UTF-8 encoding with explicit null markers, field labels, and version prefixes.

`changeKey` (`change-key-v1`) answers which logical lesson/change slot is affected. It includes the journal, change type, and the planned occurrence when available or otherwise the effective occurrence. The occurrence includes date, lesson period, subject, teacher, room, and group. Replacement teacher/subject values and unknown annotation content are excluded, so content updates retain the same logical key. VULCAN response row IDs are never accepted by the hashing API and are not persistent business identity.

`fingerprint` (`change-fingerprint-v1`) answers whether that logical change's content changed. It includes change type, planned and effective occurrences, deterministically sorted change signals, substitution replacement codes, and unknown annotations in observed list order. Text is normalized to Unicode NFC without inventing case-insensitive semantics.

Duplicate `changeKey` values in one successful input fail with a sanitized exception before database state is mutated. Canonical inputs are never logged. Raw annotations and replacement codes may affect the one-way fingerprint but are not stored.

## Persistence and transactions

Flyway exclusively owns the PostgreSQL schema. Hibernate runs with `ddl-auto=validate` and Open Session in View disabled. `tracking_scope` stores baseline and last-success state. `schedule_change_state` stores only active hashes, protocol-neutral slot metadata, and first/last-seen timestamps using PostgreSQL `timestamptz`.

One successful reconciliation is a Spring transaction. Existing scope rows are pessimistically locked, the database uniqueness constraint is authoritative for concurrent first-time creation, and a version column provides optimistic conflict detection. Active-state replacement and scope timestamp updates commit or roll back together.

The tracking tables do not contain raw VULCAN JSON, raw annotations, cookies, sessions, credentials, staff names, teacher IDs, replacement codes, or student data.
