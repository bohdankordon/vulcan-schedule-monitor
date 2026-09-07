#!/usr/bin/env bash
# Shared implementation, sourced only from these tracked scripts (never metadata).

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

value_required() { [[ $# -ge 2 && -n $2 ]] || fail "Missing value for $1"; }

init_compose() {
    local root
    root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
    env_file=${env_file:-$root/.env.production}
    output_dir=${output_dir:-$root/backups}
    [[ -f $env_file ]] || fail 'Environment file must be a regular file.'
    compose=(docker compose --env-file "$env_file" -f "$root/compose.production.yml")
    if [[ -n ${project_name:-} ]]; then compose+=(--project-name "$project_name"); fi
    "${compose[@]}" config --quiet
}

app_sql() {
    "${compose[@]}" exec -T postgres sh -c '
        export PGPASSWORD="$POSTGRES_APP_PASSWORD" PGCONNECT_TIMEOUT=10
        exec psql -X -w -h postgres -U schedule_monitor -d schedule_monitor -Atq -v ON_ERROR_STOP=1
    '
}

admin_sql() {
    "${compose[@]}" exec -T postgres sh -c '
        export PGPASSWORD="$POSTGRES_PASSWORD" PGCONNECT_TIMEOUT=10
        exec psql -X -w -h postgres -U postgres -d postgres -Atq -v ON_ERROR_STOP=1
    '
}

verify_role() {
    local flags
    flags=$(admin_sql <<'SQL'
SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls)
       AND NOT EXISTS (SELECT FROM pg_auth_members WHERE member = pg_roles.oid)
FROM pg_roles WHERE rolname = 'schedule_monitor';
SQL
    )
    [[ $flags == t ]] || fail 'Expected schedule_monitor LOGIN role with no elevated flags or memberships.'
}

verify_versions() {
    local db server_number server_version dump_version restore_version
    db=$("${compose[@]}" ps --all --quiet postgres)
    [[ -n $db && $db != *$'\n'* ]] || fail 'Expected exactly one PostgreSQL service container.'
    [[ $(docker inspect --format '{{.State.Health.Status}}' "$db") == healthy ]] || fail 'PostgreSQL service is not healthy.'
    server_number=$(admin_sql <<<'SHOW server_version_num;')
    server_version=$(admin_sql <<<'SHOW server_version;')
    dump_version=$("${compose[@]}" exec -T postgres pg_dump --version)
    restore_version=$("${compose[@]}" exec -T postgres pg_restore --version)
    [[ $server_number =~ ^18[0-9]{4}$ && $dump_version =~ ^pg_dump\ \(PostgreSQL\)\ 18[.\ ] && $restore_version =~ ^pg_restore\ \(PostgreSQL\)\ 18[.\ ] ]] || fail 'PostgreSQL server, pg_dump and pg_restore must all have major version 18.'
    printf 'Versions: server %s; %s; %s\n' "$server_version" "$dump_version" "$restore_version"
}

validate_archive() {
    # TOC output may contain private object names; do not print it.
    "${compose[@]}" exec -T postgres pg_restore --list < "$1" > /dev/null
}
