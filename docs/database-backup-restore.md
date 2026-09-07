# Database backup and restore

These Linux Bash operations protect the `schedule_monitor` logical database in
`compose.production.yml`. They use PostgreSQL tools inside its running
`postgres:18.6` service; no host PostgreSQL client is required. Read the
[container foundation](container-deployment.md) and [health/shutdown contract](operations.md)
before recovery. This runbook does not deploy a server or enable providers.

## Contents and privacy

The custom archive contains schema, tables, rows, sequences, indexes, constraints,
and `flyway_schema_history`. Durable state includes users, Telegram routing and
recipient IDs, subscriptions, VULCAN accounts/catalogs, tracking baselines,
notification outbox history, encrypted sessions, and optionally encrypted
remembered credentials. Every dump is **CONFIDENTIAL**, including synthetic-looking
operational history. Never commit dumps or upload raw archives publicly.

Cluster roles/passwords, `POSTGRES_ADMIN_PASSWORD`, `POSTGRES_APP_PASSWORD`,
`VULCAN_MASTER_KEY`, Telegram tokens, application images and infrastructure
configuration are not backed up by these scripts. They do not decrypt stored
values or export secrets from container environments. Roles remain infrastructure
configuration. Do not place plaintext infrastructure secrets in application rows.

**Preserve the same `VULCAN_MASTER_KEY` separately** in secure password/secret
storage. Encrypted VULCAN rows in a recovered database need the key used for that
database state. Losing it makes those values unrecoverable from the dump. These
tools neither copy the key nor store it in metadata, checksum or archive.

Database passwords may be **new on a replacement server**: logical dumps do not
contain roles or passwords. Bootstrap the expected `schedule_monitor` role and
ownership using the production initialization model; configure matching new
database/app credentials. Changing an env file does not rotate an existing
volume's stored passwords. The VULCAN master key has a different recovery contract.

The scripts use `umask 077`: new staging directories are private and new files
have no group/other permissions. Existing directory permissions are not rewritten;
use operator-owned directories with mode 0700 and secure the env file. Encrypted
host disk/storage is recommended. Keep copies off-host in access-controlled,
encrypted storage when introducing off-host backup operations. No backup encryption
or upload automation exists yet. SHA-256 checks integrity; it provides neither
confidentiality nor source authentication. Restore only trusted archives and sidecars.

## Prerequisites

Use Linux, Bash, Docker Compose v2, and standard GNU utilities (`sha256sum`, `cmp`,
`date`, `mktemp`, `cp`, `ln`, `tee`, `sed`). The output filesystem must support hard
links. Both scripts support `--help`. Run from the repository root as an operator
with Docker access and access to the private environment file. Defaults resolve
to the repository's `.env.production` and `backups/`; explicit paths resolve from
the caller's directory and may contain spaces. Pass the same `--project-name NAME`
used to start the stack if it differs from Compose's default. The scripts always
select `compose.production.yml`; they do not select a developer Compose file.

The PostgreSQL container must be healthy and reachable. The existing app container
must be present for restore (it may already be stopped). Use the production
bootstrap before restoring on a replacement host. The scripts check that the
server, `pg_dump`, and `pg_restore` are **major 18** and print exact versions for
diagnostics; a future 18.x minor is allowed. Cross-major migration is out of scope.
The app role must have LOGIN and all five elevated flags false, with no role
memberships. No privilege is granted or role recreated by recovery tooling.

Allow space for the archive plus its safety backup and a private temporary copy
of the input archive under `${TMPDIR:-/tmp}`. Run one restore operation at a time;
do not run another database maintenance operation concurrently. Stop unrelated
database clients before maintenance. Environment variables override env-file
values in Compose; select provider switches deliberately. Disposable verification
always forces VULCAN connection, monitoring and Telegram off.

## Online backup

```bash
bash scripts/database/backup.sh --env-file .env.production --output-dir backups
```

The actual dump authenticates as non-superuser `schedule_monitor` using the
container-held app password. It uses `pg_dump --format=custom --no-owner --no-acl`.
Administrative SQL is used only to check server/role prerequisites; the dump does
not use the cluster superuser. Passwords never appear in host command arguments,
credential URIs, output or metadata.

The app remains running. PostgreSQL's dump captures a consistent snapshot while
normal reads/writes continue; commits after that snapshot begins are naturally
absent. No application-wide lock or shutdown is added. Concurrent schema changes
can still interfere with a dump. See the [PostgreSQL 18 pg_dump contract](https://www.postgresql.org/docs/18/app-pgdump.html).

Each successful backup reports three paths:

```text
vulcan-schedule-monitor-<UTC timestamp>-<random suffix>.dump
vulcan-schedule-monitor-<UTC timestamp>-<random suffix>.dump.sha256
vulcan-schedule-monitor-<UTC timestamp>-<random suffix>.dump.meta
```

The random suffix avoids same-second collisions. A private `.partial` staging
directory receives the dump. Only after successful `pg_dump` and PostgreSQL 18
`pg_restore --list` does the script compute SHA-256 and write metadata. It publishes
sidecars with no-overwrite hard links, then publishes the complete archive with
one atomic hard link on the same filesystem. The final archive is the completion
marker and is announced only after every artifact is complete. Errors and handled
signals remove staging files; an uncatchable kill/power loss can leave hidden
staging files or orphan sidecars, which are not a completed backup. Publication
does not promise filesystem durability through power loss; verify stored copies.

The checksum line contains exactly a lowercase SHA-256 digest, two spaces, and the
archive basename. Move the three files together to another directory without
renaming them. No absolute host path is embedded. Metadata is exactly these five
ordered lines, with a valid UTC creation timestamp:

```text
format=VSM_DB_BACKUP_V1
created_utc=2026-09-07T12:00:00Z
database=schedule_monitor
server_major=18
pg_dump_major=18
```

Metadata is parsed as data and never sourced/executed. It contains no user/chat IDs,
tenant URLs, sessions, credentials or keys. `pg_restore --list` validates the
archive header/table of contents, not every compressed payload byte. A real
restore rehearsal remains necessary; the test harness also exercises payload
failure after a valid list. See [PostgreSQL 18 pg_restore](https://www.postgresql.org/docs/18/app-pgrestore.html).

## Destructive restore

Choose one exact trusted archive; do not use globs or an automatically selected
"latest" file. Replace the illustrative path below with the actual reported path:

```bash
bash scripts/database/restore.sh \
  --env-file .env.production \
  --archive 'backups/vulcan-schedule-monitor-<UTC timestamp>-<random suffix>.dump' \
  --confirm schedule_monitor \
  --output-dir backups
```

Quote the archive path when it contains spaces. Without the exact confirmation
token `schedule_monitor`, the script fails before any safety backup, shutdown or
database mutation. `--readiness-timeout SECONDS` defaults to 180 and accepts
1..9999. The restore sequence is:

1. Validate confirmation, arguments, one regular archive and both regular sidecars.
2. Copy those files into a private temporary directory. Verify the exact checksum
   line against the copied archive bytes; missing/mismatched checksums fail closed.
3. Validate the exact metadata structure, format, UTC timestamp, database and
   supported major. Metadata is never evaluated as shell code.
4. Check Compose configuration, PostgreSQL health/reachability, exact tool/server
   versions and the expected non-superuser role. Run `pg_restore --list` on the
   copied archive and check that exactly one app container exists.
5. Run the real `backup.sh` while the app is still running. Its completed safety
   backup path is printed and retained. Any failure aborts before app stop or DB
   mutation. The subsequent restore uses the same validated private input copy.
6. Gracefully `docker compose stop app`, honoring `stop_grace_period: 120s`.
   Verify the container exited and was not killed with exit 137. PostgreSQL stays
   running. No explicit SIGKILL is issued.
7. As `postgres`, disable new connections only to `schedule_monitor`, terminate
   its remaining connections, drop it and recreate it from `template0` with
   owner `schedule_monitor`. Revoke PUBLIC database access. No other database's
   sessions, roles or cluster-wide settings are modified.
8. As `schedule_monitor`, use `pg_restore --no-owner --no-acl --exit-on-error
   --single-transaction`. Source-host ownership/ACLs are not replayed. Assign
   `public` schema ownership to `schedule_monitor` and revoke PUBLIC schema access.
9. Verify database ownership and role security again: LOGIN true; `rolsuper`,
   `rolcreatedb`, `rolcreaterole`, `rolreplication`, `rolbypassrls` false; no role
   memberships. A missing/unsafe role fails; tooling never promotes it.
10. Start the existing app normally. Poll inside the app with its existing curl
    for GET `/actuator/health/readiness` HTTP 200, discarding response bodies, with
    the bounded timeout. A fixed sleep is not readiness evidence.

The dump includes Flyway history unchanged. Normal startup validates it and may
apply migrations newer than the backup. Hibernate keeps `ddl-auto=validate`.
Readiness proves application/database startup, not the validity of encrypted
VULCAN state or external provider credentials. No provider contact or decryption
is used to certify an archive.

### Safety backup override and failures

`--skip-safety-backup` is an explicit dangerous override for an already damaged,
undumpable current database. It warns and records the absence of a fresh safety
archive. It bypasses only the safety backup, never confirmation, integrity,
metadata, version, role, archive or shutdown guards. Use it only when knowingly
accepting destructive recovery risk.

If replacement fails after its first destructive step, output explicitly says
`RESTORE FAILURE`, does not certify the database, and reports the safety backup
path (or its absence). The app stays stopped. If app startup/readiness fails, the
script stops the app again and leaves its diagnostic logs intact. Inspect:

```bash
docker compose --env-file .env.production -f compose.production.yml ps --all
docker compose --env-file .env.production -f compose.production.yml logs --tail=100 app postgres
```

There are no automatic destructive retries or automatic safety-archive restores.
The operator chooses the next recovery archive deliberately and reruns the same
confirmed command; use the skip override only if the current failed state cannot
be dumped. A host crash or uncatchable signal requires checking container/database
state manually. Do not share raw logs or dump contents publicly.

## Storage lifecycle

`docker compose down` removes containers/network but retains the database volume.
`down -v` deletes that durable state; never use it as routine shutdown. Generated
`backups/` is ignored by Git and excluded from the Docker build context. Custom
output directories must also stay outside tracked content and image layers.

No cron/systemd timer, retention pruning, off-host upload, backup encryption
tooling, reverse proxy/TLS, or VPS deployment is implemented. Old backups are never
automatically deleted. Operators must account for disk space and establish these
policies separately.

## Verification

```bash
./mvnw spotless:apply
./mvnw -B -ntp verify
python3 scripts/container/verify.py
git diff --check
```

Windows uses `.\mvnw.cmd`, `python`, and installed Git Bash for the same scripts.
The container harness builds the production image once, performs the existing
Chromium/health/outage/role checks, then runs real backup/restore against a unique
Compose project/volume and generated env file. It uses synthetic probe rows only;
VULCAN connection=false, monitoring=false and Telegram=false. Provider request
counts are zero by disabled runtime configuration, not by packet capture.

Tests verify the unchanged running app during backup; original rows, sequences,
Flyway history, JPA startup, app database sessions and role security after restore;
checksum tamper, invalid archive, missing confirmation/sidecars, invalid metadata
and safety-backup destination failures before any app/DB changes. A truncated
payload with valid checksum and TOC fails after replacement: the app stays stopped
and its fresh safety backup is reported. Explicit recovery then restores a safety
archive, demonstrating it captured the state immediately preceding replacement.
Temporary backups and only the generated project/volume are removed; a final
Git-status audit rejects generated artifacts. The same verification runs inside
the bounded `Container build and smoke` CI job without a redundant full build.
