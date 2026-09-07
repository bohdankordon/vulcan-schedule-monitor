#!/usr/bin/env bash
set -Eeuo pipefail

: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set and non-empty}"

# Only invoked by the official entrypoint on a new, empty data directory.
# Read the password from the environment inside psql, never from arguments or
# shell-expanded SQL. Suppress statement/error-statement logging for this session.
PGOPTIONS='-c log_statement=none -c log_min_error_statement=panic' \
psql --no-psqlrc --quiet --no-password --username=postgres --dbname=schedule_monitor \
    --set=ON_ERROR_STOP=1 --set=ECHO=none --set=VERBOSITY=terse --set=SHOW_CONTEXT=never <<'SQL'
\getenv application_password POSTGRES_APP_PASSWORD
BEGIN;
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'schedule_monitor') THEN
        CREATE ROLE schedule_monitor;
    END IF;
END
$$;
ALTER ROLE schedule_monitor WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOREPLICATION NOBYPASSRLS PASSWORD :'application_password';
ALTER DATABASE schedule_monitor OWNER TO schedule_monitor;
REVOKE ALL ON DATABASE schedule_monitor FROM PUBLIC;
ALTER SCHEMA public OWNER TO schedule_monitor;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
COMMIT;
SQL
