#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

usage() {
    cat <<'HELP'
DESTRUCTIVE replacement of schedule_monitor from one exact trusted archive path.
Usage: bash scripts/database/restore.sh --archive PATH --confirm schedule_monitor
       [--env-file PATH] [--output-dir PATH] [--project-name NAME]
       [--skip-safety-backup] [--readiness-timeout SECONDS]
Defaults: repository .env.production, backups/ for safety backup, readiness 180s.
--skip-safety-backup is dangerous: use only when the current DB cannot be dumped
and you knowingly accept destructive recovery without a fresh recovery point.
The original VULCAN_MASTER_KEY must be preserved separately for encrypted rows.
HELP
}

archive= confirmation= skip_safety=false readiness_timeout=180
stage= destructive=false app_started=false safety_archive='NONE (safety backup skipped or not completed)'
while (($#)); do
    case $1 in
        --help) usage; exit 0 ;;
        --archive) value_required "$@"; [[ -z $archive ]] || fail 'Specify one archive only.'; archive=$2; shift 2 ;;
        --confirm) value_required "$@"; confirmation=$2; shift 2 ;;
        --env-file) value_required "$@"; env_file=$2; shift 2 ;;
        --output-dir) value_required "$@"; output_dir=$2; shift 2 ;;
        --project-name) value_required "$@"; project_name=$2; shift 2 ;;
        --readiness-timeout) value_required "$@"; readiness_timeout=$2; shift 2 ;;
        --skip-safety-backup) skip_safety=true; shift ;;
        *) fail "Unknown argument: $1" ;;
    esac
done
[[ $confirmation == schedule_monitor ]] || fail 'Restore requires --confirm schedule_monitor.'
[[ $readiness_timeout =~ ^[1-9][0-9]{0,3}$ ]] || fail 'Readiness timeout must be 1..9999 seconds.'
[[ -n $archive && -f $archive ]] || fail 'Archive must be one exact regular file.'
[[ -f $archive.sha256 && -f $archive.meta ]] || fail 'Checksum and metadata sidecars are required.'
name=$(basename -- "$archive")
[[ $name != *$'\n'* && $name != *$'\r'* && $name != *\\* ]] || fail 'Unsupported archive filename.'

cleanup() {
    local rc=$?
    trap - EXIT
    if ((rc != 0)) && $destructive; then
        printf 'RESTORE FAILURE: database replacement started; database is not certified valid.\n' >&2
        if $app_started; then "${compose[@]}" stop app >&2 || true; fi
        printf 'App must remain STOPPED. Safety backup: %s\nNo automatic recovery attempted; inspect retained Compose logs.\n' "$safety_archive" >&2
    fi
    if [[ -n $stage ]]; then
        rm -f -- "$stage/archive" "$stage/checksum" "$stage/metadata" "$stage/backup.log"
        rmdir -- "$stage"
    fi
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# Validate and restore the SAME private copy even if the source path is replaced.
stage=$(mktemp -d "${TMPDIR:-/tmp}/vsm-restore-XXXXXXXXXX")
cp -- "$archive" "$stage/archive"
cp -- "$archive.sha256" "$stage/checksum"
cp -- "$archive.meta" "$stage/metadata"
digest=$(sha256sum < "$stage/archive")
printf '%s  %s\n' "${digest%% *}" "$name" | cmp -s - "$stage/checksum" || fail 'Checksum mismatch or invalid sidecar.'
mapfile -t metadata < "$stage/metadata"
[[ ${#metadata[@]} == 5 && ${metadata[0]} == format=VSM_DB_BACKUP_V1 \
    && ${metadata[1]} =~ ^created_utc=[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ \
    && ${metadata[2]} == database=schedule_monitor && ${metadata[3]} == server_major=18 \
    && ${metadata[4]} == pg_dump_major=18 ]] || fail 'Invalid backup metadata (expected VSM_DB_BACKUP_V1, schedule_monitor, major 18).'
date -u -d "${metadata[1]#created_utc=}" +%Y-%m-%dT%H:%M:%SZ > /dev/null || fail 'Invalid metadata timestamp.'
init_compose
verify_versions
verify_role
validate_archive "$stage/archive"
app=$("${compose[@]}" ps --all --quiet app)
[[ -n $app && $app != *$'\n'* ]] || fail 'Expected exactly one existing application container.'
if ! $skip_safety; then
    backup_args=(--env-file "$env_file" --output-dir "$output_dir")
    if [[ -n ${project_name:-} ]]; then backup_args+=(--project-name "$project_name"); fi
    # Keep backup success visible and capture its exact path without sourcing output.
    bash "$(dirname -- "${BASH_SOURCE[0]}")/backup.sh" "${backup_args[@]}" | tee "$stage/backup.log"
    safety_archive=$(sed -n 's/^Archive: //p' "$stage/backup.log")
    [[ -n $safety_archive && -f $safety_archive ]] || fail 'Safety backup did not report a completed archive.'
else
    printf 'WARNING: safety backup explicitly skipped; accepting destructive recovery risk.\n' >&2
fi
"${compose[@]}" stop app
[[ $(docker inspect --format '{{.State.Status}}' "$app") == exited ]] || fail 'Application is not stopped.'
[[ $(docker inspect --format '{{.State.ExitCode}}' "$app") != 137 ]] || fail 'Application did not stop gracefully; refusing replacement.'

destructive=true
# Disable new connections before terminating only this database's sessions.
# Cluster administration never runs as the application role.
admin_sql <<'SQL' > /dev/null
ALTER DATABASE schedule_monitor ALLOW_CONNECTIONS false;
SELECT pg_terminate_backend(pid) FROM pg_stat_activity
WHERE datname = 'schedule_monitor' AND pid <> pg_backend_pid();
DROP DATABASE schedule_monitor;
CREATE DATABASE schedule_monitor OWNER schedule_monitor TEMPLATE template0;
REVOKE ALL ON DATABASE schedule_monitor FROM PUBLIC;
SQL
"${compose[@]}" exec -T postgres sh -c '
    export PGPASSWORD="$POSTGRES_APP_PASSWORD" PGCONNECT_TIMEOUT=10
    exec pg_restore --host=postgres --username=schedule_monitor --dbname=schedule_monitor \
        --no-password --no-owner --no-acl --exit-on-error --single-transaction
' < "$stage/archive"
app_sql <<'SQL' > /dev/null
ALTER SCHEMA public OWNER TO schedule_monitor;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
SQL
verify_role
[[ $(app_sql <<<'SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database();') == schedule_monitor ]] || fail 'Restored database owner mismatch.'
app_started=true
"${compose[@]}" start app
deadline=$((SECONDS + readiness_timeout))
while ((SECONDS < deadline)); do
    code=$("${compose[@]}" exec -T app curl --silent --output /dev/null --write-out '%{http_code}' \
        --connect-timeout 2 --max-time 5 http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null) || code=000
    if [[ $code == 200 ]]; then
        printf 'RESTORE SUCCESS: application readiness HTTP 200.\nSafety backup: %s\n' "$safety_archive"
        destructive=false
        exit 0
    fi
    sleep 1
done
fail 'Application readiness did not return HTTP 200 within the timeout; inspect Compose logs.'
