#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
source "$(dirname -- "${BASH_SOURCE[0]}")/common.sh"

usage() {
    cat <<'HELP'
Online backup of schedule_monitor using the running production PostgreSQL service.
Usage: bash scripts/database/backup.sh [--env-file PATH] [--output-dir PATH]
                                     [--project-name NAME]
Defaults: repository .env.production and backups/. Explicit paths use caller's cwd.
Keep the .dump, .dump.sha256 and .dump.meta together. All contain confidential state
or operational information. No retention deletion or off-host upload is performed.
HELP
}

while (($#)); do
    case $1 in
        --help) usage; exit 0 ;;
        --env-file) value_required "$@"; env_file=$2; shift 2 ;;
        --output-dir) value_required "$@"; output_dir=$2; shift 2 ;;
        --project-name) value_required "$@"; project_name=$2; shift 2 ;;
        *) fail "Unknown argument: $1" ;;
    esac
done
init_compose
verify_versions
verify_role
mkdir -p -- "$output_dir"
output_dir=$(cd -- "$output_dir" && pwd)
stage=$(mktemp -d "$output_dir/.vsm-backup-XXXXXXXXXX.partial")
name="vulcan-schedule-monitor-$(date -u +%Y%m%dT%H%M%SZ)-${stage##*/.vsm-backup-}"
name=${name%.partial}.dump
archive=$output_dir/$name
checksum_published=false
metadata_published=false
cleanup() {
    local rc=$?
    trap - EXIT
    # Publish archive last. Never remove a completed archive, including on a signal.
    if [[ ! -f $archive ]]; then
        if $checksum_published; then rm -f -- "$archive.sha256"; fi
        if $metadata_published; then rm -f -- "$archive.meta"; fi
    fi
    rm -f -- "$stage/archive.partial" "$stage/checksum" "$stage/metadata"
    rmdir -- "$stage"
    exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"${compose[@]}" exec -T postgres sh -c '
    export PGPASSWORD="$POSTGRES_APP_PASSWORD" PGCONNECT_TIMEOUT=10
    exec pg_dump --host=postgres --username=schedule_monitor --dbname=schedule_monitor \
        --no-password --format=custom --no-owner --no-acl
' > "$stage/archive.partial"
validate_archive "$stage/archive.partial"
digest=$(sha256sum < "$stage/archive.partial")
printf '%s  %s\n' "${digest%% *}" "$name" > "$stage/checksum"
printf 'format=VSM_DB_BACKUP_V1\ncreated_utc=%s\ndatabase=schedule_monitor\nserver_major=18\npg_dump_major=18\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$stage/metadata"
# Hard links publish complete bytes atomically and fail rather than overwrite.
# Staging is on the same filesystem. Sidecars exist before the final archive does.
ln -T -- "$stage/checksum" "$archive.sha256"
checksum_published=true
ln -T -- "$stage/metadata" "$archive.meta"
metadata_published=true
ln -T -- "$stage/archive.partial" "$archive"
printf 'BACKUP SUCCESS\nArchive: %s\nChecksum: %s\nMetadata: %s\n' "$archive" "$archive.sha256" "$archive.meta"
