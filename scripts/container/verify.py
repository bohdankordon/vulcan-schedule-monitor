"""Disposable, provider-free image/Compose verification (Python standard library only)."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
IMAGE = "vulcan-schedule-monitor:production"
SMOKE_IMAGE = "vulcan-schedule-monitor:browser-smoke"
# Never inherit operator credentials, provider switches, JVM agents or Compose overrides.
ENV = {
    key: value for key, value in os.environ.items()
    if not key.upper().startswith(("VULCAN_", "TELEGRAM_", "SPRING_", "POSTGRES_", "COMPOSE_"))
    and key.upper() not in {
        "APP_IMAGE", "APP_PORT", "PUBLIC_BASE_URL", "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
    }
}
# SQL metacharacters exercise psql literal quoting as well as secret redaction.
MARKERS = ["synthetic-admin-" + secrets.token_hex(16),
           "synthetic-app-" + secrets.token_hex(16) + "';--",
           base64.b64encode(secrets.token_bytes(32)).decode()]
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def run(*args, timeout=180, check=True):
    result = subprocess.run(args, cwd=ROOT, env=ENV, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if check and result.returncode:
        # Never echo command arguments or interpolated Compose config.
        output = (result.stdout + result.stderr)[-12000:]
        for marker in MARKERS:
            output = output.replace(marker, "[synthetic secret redacted]")
        raise RuntimeError(f"{args[0]} failed ({result.returncode}):\n{output}")
    return result.stdout.strip()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(description, predicate, timeout=150):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            print(description + ": PASS", flush=True)
            return
        time.sleep(1)  # Poll a condition; no fixed startup delay.
    raise TimeoutError(description)


def inspect(container):
    return json.loads(run("docker", "inspect", container))[0]


def audit_image_layers(directory):
    archive = directory / "image-audit.tar"
    run("docker", "image", "save", "--output", str(archive), IMAGE, timeout=180)
    markers = [marker.encode() for marker in MARKERS]
    overlap = max(map(len, markers)) - 1
    with tarfile.open(archive) as saved:
        manifest = json.load(saved.extractfile("manifest.json"))
        for layer in {name for image in manifest for name in image["Layers"]}:
            with tarfile.open(fileobj=saved.extractfile(layer), mode="r|*") as contents:
                for member in contents:
                    if not member.isfile():
                        continue
                    with contents.extractfile(member) as source:
                        tail = b""
                        while chunk := source.read(1024 * 1024):
                            chunk = tail + chunk
                            require(all(marker not in chunk for marker in markers), "Secret in image layer")
                            tail = chunk[-overlap:]
    archive.unlink()
    print("Admin/app/key synthetic markers absent from every exported image layer: PASS", flush=True)


def application_sql(container, query, denied=False):
    # Authenticate over the Compose network, using the existing container env.
    # No password is placed in host process arguments or printed diagnostics.
    result = subprocess.run(
        ["docker", "exec", "--interactive", container, "bash", "-c",
         'export PGPASSWORD="$POSTGRES_APP_PASSWORD"; '
         'exec psql --no-psqlrc --no-password --host=postgres --username=schedule_monitor '
         '--dbname=schedule_monitor --tuples-only --no-align '
         '--set=ON_ERROR_STOP=1 --set=VERBOSITY=verbose'],
        input=query, cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=30)
    if denied:
        require(result.returncode == 3 and "42501" in result.stderr,
                "Application cluster-administration attempt must fail with insufficient_privilege")
    else:
        require(result.returncode == 0, "Application-role SQL verification failed (output withheld)")
    return result.stdout.strip()


def verify_application_role(container):
    flags = application_sql(container, """
        SELECT current_user, session_user, rolcanlogin, rolsuper, rolcreatedb,
               rolcreaterole, rolreplication, rolbypassrls
        FROM pg_roles WHERE rolname = current_user;
    """)
    require(flags == "schedule_monitor|schedule_monitor|t|f|f|f|f|f", "Unsafe application role flags")
    ownership = application_sql(container, """
        SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database();
        SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public';
        SELECT count(*) > 0 AND bool_and(tableowner = current_user)
            FROM pg_tables WHERE schemaname = 'public';
        SELECT count(*) > 0 AND bool_and(installed_by = current_user AND success)
            FROM public.flyway_schema_history;
        SELECT count(*) = 0 FROM pg_auth_members
            WHERE member = (SELECT oid FROM pg_roles WHERE rolname = current_user);
        SELECT count(*) > 0 FROM pg_stat_activity
            WHERE usename = current_user AND datname = current_database() AND pid <> pg_backend_pid();
    """)
    require(ownership.splitlines() == ["schedule_monitor", "schedule_monitor", "t", "t", "t", "t"],
            "Database/schema ownership, Flyway identity, memberships or live app datasource mismatch")
    print("Application SQL identity=schedule_monitor; LOGIN; all five elevated flags=false; "
          "database/schema/table ownership, Flyway history and live app sessions: PASS", flush=True)


def status(port, path):
    try:
        with HTTP.open(f"http://127.0.0.1:{port}/actuator/health{path}", timeout=40) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (OSError, urllib.error.URLError):
        return 0


def validate_config(config):
    app, db = config["services"]["app"], config["services"]["postgres"]
    require(set(config["services"]) == {"app", "postgres"}, "Unexpected service")
    require(not db.get("ports"), "PostgreSQL must not publish ports")
    require(len(app["ports"]) == 1 and app["ports"][0]["host_ip"] == "127.0.0.1"
            and app["ports"][0]["target"] == 8080, "App must publish loopback only")
    require(app["depends_on"]["postgres"]["condition"] == "service_healthy", "DB startup gate missing")
    require(app["restart"] == db["restart"] == "unless-stopped", "Restart policy mismatch")
    require(app["stop_grace_period"] == "2m0s" and app["init"], "Shutdown contract missing")
    require(db["image"] == "postgres:18.6", "PostgreSQL version drift")
    require("pg_isready" in " ".join(db["healthcheck"]["test"]), "DB probe missing")
    require(any(v["type"] == "volume" and v["source"] == "postgres_data"
                and v["target"] == "/var/lib/postgresql" for v in db["volumes"]), "DB persistence missing")
    require("postgres_data" in config["volumes"], "Named volume missing")
    require(any(v["type"] == "bind" and v["target"] == "/docker-entrypoint-initdb.d"
                and v["read_only"] and Path(v["source"]).resolve() == (ROOT / "docker/postgres/init").resolve()
                for v in db["volumes"]), "Read-only PostgreSQL initialization mount missing")
    require(not app.get("volumes") and app["read_only"] and app["tmpfs"], "App storage must be ephemeral")
    require(app["shm_size"] == "268435456", "Bounded shared memory missing")
    require(app["cap_drop"] == ["ALL"] and "no-new-privileges:true" in app["security_opt"], "App isolation missing")
    for service in (app, db):
        require(not service.get("privileged") and not service.get("cap_add"), "Excess privilege")
        require(service.get("network_mode") != "host" and service.get("ipc") != "host", "Host namespace")
        require(service["logging"]["options"] == {"max-size": "10m", "max-file": "3"}, "Log rotation missing")
    env = app["environment"]
    require(all(env[k] == "false" for k in (
        "VULCAN_CONNECTION_ENABLED", "VULCAN_MONITORING_ENABLED", "TELEGRAM_BOT_ENABLED")), "Providers enabled")
    require(env["SPRING_DATASOURCE_URL"] == "jdbc:postgresql://postgres:5432/schedule_monitor", "Datasource mismatch")
    require(db["environment"]["POSTGRES_USER"] == "postgres"
            and db["environment"]["POSTGRES_PASSWORD"] == MARKERS[0]
            and db["environment"]["POSTGRES_APP_PASSWORD"] == MARKERS[1]
            and env["SPRING_DATASOURCE_USERNAME"] == "schedule_monitor"
            and env["SPRING_DATASOURCE_PASSWORD"] == MARKERS[1]
            and MARKERS[0] not in env.values(), "Separate admin/application credentials required")
    print("Compose topology, safe defaults, persistence, security and stop budget: PASS", flush=True)


def verify_database_operations(directory, env_file, project, compose, app, db, port):
    # Git Bash lets Windows developers exercise the exact Linux production scripts.
    # Linux CI uses system Bash. No alternative production implementation exists.
    bash = shutil.which("bash")
    if os.name == "nt":
        git = shutil.which("git")
        candidate = Path(git).resolve().parents[1] / "bin/bash.exe" if git else Path("missing")
        bash = str(candidate) if candidate.is_file() else None
    require(bash is not None, "Bash required for real backup/restore verification (Git Bash on Windows)")
    output = directory / "backup artifacts"
    common = ["--env-file", env_file.as_posix(), "--project-name", project,
              "--output-dir", output.as_posix()]

    def script(name, *args, failure=None):
        script_env = dict(ENV)
        if os.name == "nt":
            # Preserve the container's null-device argument for curl while still
            # translating host Compose/env paths for native docker.exe.
            script_env["MSYS2_ARG_CONV_EXCL"] = "/dev/null"
        result = subprocess.run([bash, (ROOT / "scripts/database" / name).as_posix(), *common, *args],
                                cwd=ROOT, env=script_env, capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=480)
        transcript = result.stdout + result.stderr
        require(all(marker not in transcript for marker in MARKERS), "Secret in backup/restore output")
        if failure:
            require(result.returncode != 0 and failure in transcript,
                    "Expected restore guard: " + failure + "\n" + transcript[-4000:])
        else:
            require(result.returncode == 0, name + " failed:\n" + transcript[-6000:])
        return transcript

    def marker():
        return application_sql(db, "SELECT id, value FROM recovery_probe ORDER BY id;")

    def unchanged(before, expected):
        after = inspect(app)
        require(after["State"]["Running"] and after["State"]["StartedAt"] == before["State"]["StartedAt"]
                and after["RestartCount"] == before["RestartCount"], "Rejected restore stopped/restarted app")
        require(marker() == expected, "Rejected restore mutated the database")
        require(status(port, "/readiness") == 200, "Rejected restore affected readiness")

    def fixture(name, contents, original):
        path = output / name
        path.write_bytes(contents)
        Path(str(path) + ".sha256").write_text(hashlib.sha256(contents).hexdigest() + "  " + name + "\n", newline="\n")
        shutil.copyfile(str(original) + ".meta", str(path) + ".meta")
        return path

    application_sql(db, """
        CREATE TABLE recovery_probe (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, value text NOT NULL);
        INSERT INTO recovery_probe(value) VALUES ('original-synthetic-state');
    """)
    original_marker = marker()
    history = application_sql(db, "SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank;")
    before = inspect(app)
    transcript = script("backup.sh")
    archives = list(output.glob("*.dump"))
    require(len(archives) == 1 and "BACKUP SUCCESS" in transcript, "Backup artifact missing")
    archive = archives[0]
    unchanged(before, original_marker)
    require(archive.read_bytes().startswith(b"PGDMP"), "Expected PostgreSQL custom archive")
    require(Path(str(archive) + ".sha256").read_text() == hashlib.sha256(archive.read_bytes()).hexdigest()
            + "  " + archive.name + "\n", "Checksum must match final archive bytes")
    require(not list(output.glob("*.partial")), "Backup left partial files")
    if os.name != "nt":
        require(all(p.stat().st_mode & 0o077 == 0 for p in output.iterdir()), "Backup permissions too broad")
    print("Real online custom backup as non-superuser; app uninterrupted; checksum/sidecars/permissions: PASS", flush=True)
    application_sql(db, "UPDATE recovery_probe SET value='post-backup-mutation'; INSERT INTO recovery_probe(value) VALUES ('new-row');")
    mutated = marker()

    # Every pre-destructive guard checks the live DB and original app process.
    script("restore.sh", "--archive", archive.as_posix(), failure="requires --confirm")
    unchanged(before, mutated)
    require(len(list(output.glob("*.dump"))) == 1, "Confirmation guard made a safety backup")
    print("Missing confirmation rejected before safety backup/app stop/DB mutation: PASS", flush=True)
    tampered = fixture("tampered.dump", archive.read_bytes() + b"tamper", archive)
    Path(str(tampered) + ".sha256").write_text(
        hashlib.sha256(archive.read_bytes()).hexdigest() + "  " + tampered.name + "\n", newline="\n")
    script("restore.sh", "--archive", tampered.as_posix(), "--confirm", "schedule_monitor", failure="Checksum mismatch")
    unchanged(before, mutated)
    print("Checksum tamper rejected; data/app unchanged: PASS", flush=True)
    invalid = fixture("invalid.dump", b"not a PostgreSQL archive\n", archive)
    script("restore.sh", "--archive", invalid.as_posix(), "--confirm", "schedule_monitor", failure="pg_restore: error")
    unchanged(before, mutated)
    print("Invalid archive with matching checksum/metadata rejected; data/app unchanged: PASS", flush=True)
    for missing in (".sha256", ".meta"):
        sidecar = Path(str(invalid) + missing)
        saved = sidecar.read_bytes()
        sidecar.unlink()
        script("restore.sh", "--archive", invalid.as_posix(), "--confirm", "schedule_monitor", failure="sidecars are required")
        unchanged(before, mutated)
        sidecar.write_bytes(saved)
    for replacement in ("database=wrong", "server_major=17", "pg_dump_major=17", "format=VSM_DB_BACKUP_V2"):
        key = replacement.split("=", 1)[0]
        meta = Path(str(archive) + ".meta").read_text()
        lines = [replacement if line.startswith(key + "=") else line for line in meta.splitlines()]
        Path(str(invalid) + ".meta").write_text("\n".join(lines) + "\n", newline="\n")
        script("restore.sh", "--archive", invalid.as_posix(), "--confirm", "schedule_monitor", failure="Invalid backup metadata")
        unchanged(before, mutated)
    print("Missing sidecars and metadata format/database/major guards: PASS", flush=True)
    destination_file = directory / "not-a-directory"
    destination_file.write_text("synthetic fixture")
    script("restore.sh", "--archive", archive.as_posix(), "--confirm", "schedule_monitor",
           "--output-dir", destination_file.as_posix(), failure="mkdir:")
    unchanged(before, mutated)
    print("Safety backup destination failure aborts before app stop/DB mutation: PASS", flush=True)

    # Exercise actual container config while the normal env file remains disabled.
    # Enabled fixtures never run Java: running cases use only sleep with no network;
    # missing/malformed cases are created but never started. PostgreSQL is untouched.
    db_before = inspect(db)
    override = directory / "provider-fixture.json"
    for provider in ("TELEGRAM_BOT_ENABLED", "VULCAN_CONNECTION_ENABLED", "VULCAN_MONITORING_ENABLED"):
        for value in ("true", None, "FALSE"):
            override.write_text(json.dumps({"services": {"app": {
                "environment": {provider: value}, "entrypoint": ["sleep", "infinity"],
                "network_mode": "none", "restart": "no", "healthcheck": {"disable": True},
            }}}), encoding="utf-8")
            try:
                run(*compose, "-f", str(override), "up", "--no-start", "--no-deps",
                    "--force-recreate", "--no-build", "app")
                fixture_app = run(*compose, "ps", "--all", "--quiet", "app")
                selected = run("docker", "inspect", "--format",
                               '{{range .Config.Env}}{{if eq (index (split . "=") 0) "' + provider
                               + '"}}{{println .}}{{end}}{{end}}', fixture_app)
                # Compose/Docker may retain an unset key without an assignment.
                expected = ("", provider, provider + "=") if value is None else (provider + "=" + value,)
                require(selected in expected,
                        "Provider fixture environment mismatch (values withheld)")
                require(run("docker", "inspect", "--format", '{{json .Config.Entrypoint}}', fixture_app)
                        == '["sleep","infinity"]'
                        and run("docker", "inspect", "--format", '{{.HostConfig.NetworkMode}}', fixture_app)
                        == "none", "Provider fixture must be inert and network-isolated")
                if value == "true":
                    run("docker", "start", fixture_app)  # Starts sleep only, never the app.
                state_before = run("docker", "inspect", "--format", '{{json .State}}', fixture_app)
                require(json.loads(state_before)["Running"] == (value == "true"), "Fixture process state mismatch")
                artifacts_before = {p.name: p.read_bytes() for p in output.iterdir()}
                for options in ((), ("--skip-safety-backup",)):
                    script("restore.sh", "--archive", archive.as_posix(), "--confirm", "schedule_monitor",
                           *options, failure="Restore requires the app container to have VULCAN and Telegram providers disabled.")
                    require(run("docker", "inspect", "--format", '{{json .State}}', fixture_app) == state_before,
                            "Provider rejection stopped/started/changed the app process")
                    require(marker() == mutated, "Provider rejection mutated the database")
                    require({p.name: p.read_bytes() for p in output.iterdir()} == artifacts_before,
                            "Provider rejection created/changed backup artifacts")
                label = "enabled/running" if value == "true" else "missing/stopped" if value is None else "malformed/stopped"
                print(f"Provider guard {provider} {label}: PASS; process/data/backups unchanged; skip cannot bypass", flush=True)
            finally:
                # Restore real app configuration to false after every fixture, but
                # defer Java startup until all negative cases are finished.
                run(*compose, "up", "--no-start", "--no-deps", "--force-recreate", "--no-build", "app")
    # Exercise the documented operator command: recreate ONLY app, then prove the
    # PostgreSQL container, process and volume were neither restarted nor replaced.
    run(*compose, "up", "-d", "--no-deps", "--force-recreate", "--no-build", "app")
    app = run(*compose, "ps", "--quiet", "app")
    wait_for("Provider-disabled app recreation readiness HTTP 200", lambda: status(port, "/readiness") == 200)
    db_after = inspect(db)
    require(run(*compose, "ps", "--quiet", "postgres") == db
            and db_after["State"]["StartedAt"] == db_before["State"]["StartedAt"]
            and db_after["RestartCount"] == db_before["RestartCount"]
            and {m["Destination"]: m for m in db_after["Mounts"]}
            == {m["Destination"]: m for m in db_before["Mounts"]}, "App-only recreation changed PostgreSQL")
    require(marker() == mutated, "App-only recreation mutated probe data")
    print("Documented app-only provider-disable recreation preserves PostgreSQL process/volume/data: PASS", flush=True)

    previous = set(output.glob("*.dump"))
    transcript = script("restore.sh", "--archive", archive.as_posix(), "--confirm", "schedule_monitor")
    safety = set(output.glob("*.dump")) - previous
    require(len(safety) == 1 and "RESTORE SUCCESS" in transcript, "Automatic safety backup/restore missing")
    safety_archive = safety.pop()
    require(marker() == original_marker, "Original marker not recovered or post-backup mutation remains")
    require(application_sql(db, "SELECT installed_rank, version, checksum, success FROM flyway_schema_history ORDER BY installed_rank;")
            == history, "Flyway history changed during recovery")
    require(status(port, "/readiness") == 200, "Post-restore readiness failed")
    verify_application_role(db)
    application_sql(db, "INSERT INTO recovery_probe(value) VALUES ('sequence-check');")
    require(marker().splitlines()[-1] == "2|sequence-check", "Sequence state did not restore")
    print("Real restore + automatic safety backup; original data/sequences/Flyway/JPA/readiness/role/session checks: PASS", flush=True)

    # A custom archive TOC is at the front: removing its final byte preserves --list
    # but makes payload restore fail. No production failure-injection flag is needed.
    damaged = fixture("damaged-payload.dump", archive.read_bytes()[:-1], archive)
    previous = set(output.glob("*.dump"))
    transcript = script("restore.sh", "--archive", damaged.as_posix(), "--confirm", "schedule_monitor",
                        failure="RESTORE FAILURE")
    recovery = set(output.glob("*.dump")) - previous
    require(len(recovery) == 1 and not inspect(app)["State"]["Running"], "Failed destructive restore must leave app stopped")
    require(recovery.pop().name in transcript, "Failure must report safety backup path")
    print("Damaged payload after DROP: explicit failure, app stopped, safety backup reported: PASS", flush=True)
    # Deliberate next operator decision: recover the earlier safety snapshot. This
    # also verifies that safety backup captured the post-backup mutation.
    script("restore.sh", "--archive", safety_archive.as_posix(), "--confirm", "schedule_monitor", "--skip-safety-backup")
    require(marker() == mutated, "Safety backup did not preserve pre-restore current data")
    verify_application_role(db)
    require(status(port, "/readiness") == 200, "Recovery after failed restore not ready")
    print("Explicit recovery from safety archive with dangerous skip override; pre-restore data recovered: PASS", flush=True)
    logs = run(*compose, "logs", "--no-color")
    require(all(secret not in logs for secret in MARKERS), "Secret leaked during backup/restore")
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use an already built production image")
    args = parser.parse_args()
    (ROOT / "target").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="container-smoke-", dir=ROOT / "target") as directory:
        directory = Path(directory)
        env_file = directory / "synthetic.env"
        # Choose a free loopback port; Compose fails safely if another process claims it.
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        quoted_app_password = MARKERS[1].replace("'", "\\'")
        env_file.write_text(f"POSTGRES_ADMIN_PASSWORD={MARKERS[0]}\n"
                            f"POSTGRES_APP_PASSWORD='{quoted_app_password}'\nVULCAN_MASTER_KEY={MARKERS[2]}\n"
                            f"APP_PORT={port}\nAPP_IMAGE={IMAGE}\n", encoding="utf-8")
        project = "vsm-smoke-" + uuid.uuid4().hex[:12]
        base = ["docker", "compose", "--project-name", project, "--env-file", str(env_file),
                "-f", str(ROOT / "compose.production.yml")]
        compose = base
        config = json.loads(run(*base, "config", "--format", "json"))
        validate_config(config)
        # All mandatory secrets must fail clearly on their own; output stays private.
        for missing in ("POSTGRES_ADMIN_PASSWORD", "POSTGRES_APP_PASSWORD", "VULCAN_MASTER_KEY"):
            absent = directory / "missing.env"
            absent.write_text("\n".join(line for line in env_file.read_text().splitlines()
                                        if not line.startswith(missing + "=")), encoding="utf-8")
            failed = subprocess.run(["docker", "compose", "--env-file", str(absent), "-f",
                                     str(ROOT / "compose.production.yml"), "config", "--quiet"],
                                    cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=30)
            require(failed.returncode != 0 and missing in failed.stderr, "Missing secret must fail clearly")
        print("Missing POSTGRES_ADMIN_PASSWORD / POSTGRES_APP_PASSWORD / VULCAN_MASTER_KEY rejection: PASS", flush=True)
        if not args.skip_build:
            print("Building production image (Maven and Chromium downloads allowed)...", flush=True)
            run("docker", "build", "--progress=plain", "-t", IMAGE, ".", timeout=1200)
        print("Production image build: PASS" if not args.skip_build else "Using existing production image", flush=True)
        image = json.loads(run("docker", "image", "inspect", IMAGE))[0]
        history = run("docker", "history", "--no-trunc", IMAGE)
        require(all(marker not in history + json.dumps(image) for marker in MARKERS), "Image secret leakage")
        require(not any(item.split("=", 1)[0].startswith(("POSTGRES_", "SPRING_DATASOURCE_", "VULCAN_", "TELEGRAM_"))
                        for item in image["Config"]["Env"]), "Runtime secrets must not be image environment")
        print("Synthetic markers absent from image config/environment and full history: PASS", flush=True)
        audit_image_layers(directory)
        require(image["Config"]["User"] == "10001:10001", "Non-root image user required")
        require(image["Config"]["Entrypoint"] == ["java", "-jar", "application.jar"], "Java must receive SIGTERM")
        require(image["Config"]["StopSignal"] == "SIGTERM", "SIGTERM required")
        health = image["Config"]["Healthcheck"]
        require(health["Test"][0] == "CMD" and health["Test"][-1].endswith("/actuator/health/liveness")
                and health["Interval"] == 30_000_000_000 and health["Timeout"] == 5_000_000_000
                and health["StartPeriod"] == 60_000_000_000 and health["Retries"] == 3, "Liveness probe mismatch")
        # java -version writes stderr; inspect it without relying on a shell pipeline.
        version = subprocess.run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "java", IMAGE,
                                  "-version"], cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=30)
        require(version.returncode == 0 and 'version "21.' in version.stderr, "Java 21 required")
        print(version.stderr.strip(), flush=True)
        uid = run("docker", "run", "--rm", "--network", "none", "--entrypoint", "id", IMAGE, "-u")
        require(uid == "10001", "Root runtime")
        run("docker", "run", "--rm", "--network", "none", "--entrypoint", "sh", IMAGE, "-c",
            "test -r /app/application.jar && test ! -w /app/application.jar && test ! -w /app/lib "
            "&& test ! -e /smoke && test ! -e /build && test ! -e /opt/java/openjdk/bin/javac")
        # XML parsing plus the CLI and library in the *built image* guards the installed browser version.
        pom = ET.parse(ROOT / "pom.xml")
        pw_version = pom.findtext("{*}properties/{*}playwright.version")
        cli_version = run("docker", "run", "--rm", "--network", "none", "--entrypoint", "java", IMAGE,
                          "-cp", "/app/lib/*", "com.microsoft.playwright.CLI", "--version")
        require(cli_version == "Version " + pw_version, "pom/image Playwright version drift")
        run("docker", "run", "--rm", "--network", "none", "--entrypoint", "test", IMAGE,
            "-r", f"/app/lib/playwright-{pw_version}.jar")
        print(f"UID={uid}; immutable application; pom/image Playwright={pw_version}: PASS", flush=True)
        run("docker", "build", "--target", "browser-smoke", "-t", SMOKE_IMAGE, ".", timeout=1200)
        print(run("docker", "run", "--rm", "--init", "--network", "none", "--read-only", "--cap-drop", "ALL",
                  "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:rw,nosuid,nodev,exec,size=512m,mode=1777",
                  "--shm-size", "256m", SMOKE_IMAGE), flush=True)
        require(not run(*compose, "ps", "--all", "--quiet"), "Disposable project unexpectedly exists")
        try:
            run(*compose, "up", "--detach", "--no-build", "--wait", "--wait-timeout", "180", timeout=240)
            app = run(*compose, "ps", "--quiet", "app")
            db = run(*compose, "ps", "--quiet", "postgres")
            runtime_env = inspect(app)["Config"]["Env"]
            require("SPRING_DATASOURCE_USERNAME=schedule_monitor" in runtime_env
                    and "SPRING_DATASOURCE_PASSWORD=" + MARKERS[1] in runtime_env
                    and not any(MARKERS[0] in item or item.startswith("POSTGRES_ADMIN_PASSWORD=")
                                for item in runtime_env), "App must receive only the application credential")
            require(all(k + "=false" in runtime_env for k in (
                "VULCAN_CONNECTION_ENABLED", "VULCAN_MONITORING_ENABLED", "TELEGRAM_BOT_ENABLED")),
                "Provider defaults must remain disabled in the running container")
            require(inspect(app)["State"]["Health"]["Status"] == "healthy"
                    and inspect(db)["State"]["Health"]["Status"] == "healthy", "Container health")
            for path in ("", "/liveness", "/readiness"):
                require(status(port, path) == 200, "Healthy endpoint failed: " + path)
            print("App + PostgreSQL healthy; root/liveness/readiness HTTP 200: PASS", flush=True)
            startup_logs = run(*compose, "logs", "--no-color", "app")
            require("Successfully applied" in startup_logs and "Initialized JPA EntityManagerFactory" in startup_logs,
                    "Flyway migration/JPA initialization evidence missing")
            verify_application_role(db)
            application_sql(db, "CREATE DATABASE forbidden_smoke_database;", denied=True)
            application_sql(db, "CREATE ROLE forbidden_smoke_role;", denied=True)
            print("Application CREATE DATABASE and CREATE ROLE: denied (SQLSTATE 42501): PASS", flush=True)
            db_volume = next(m["Name"] for m in inspect(db)["Mounts"] if m["Type"] == "volume")
            before = inspect(app)
            run(*compose, "stop", "postgres")
            wait_for("DB outage readiness HTTP 503", lambda: status(port, "/readiness") == 503)
            require(status(port, "/liveness") == 200, "DB outage changed liveness")
            wait_for("Docker liveness healthcheck continues during DB outage",
                     lambda: inspect(app)["State"]["Health"]["Log"][-1]["Start"]
                     > before["State"]["Health"]["Log"][-1]["Start"])
            after = inspect(app)
            require(after["State"]["Health"]["Status"] == "healthy"
                    and after["RestartCount"] == before["RestartCount"] == 0
                    and after["State"]["StartedAt"] == before["State"]["StartedAt"], "App restarted during DB outage")
            print("DB outage liveness HTTP 200; container healthy; restart count=0: PASS", flush=True)
            run(*compose, "start", "postgres")
            wait_for("DB restart readiness HTTP 200", lambda: status(port, "/readiness") == 200)
            require(db_volume == next(m["Name"] for m in inspect(db)["Mounts"] if m["Type"] == "volume"),
                    "PostgreSQL restart must reuse its named volume")
            verify_application_role(db)
            db_logs = run(*compose, "logs", "--no-color", "postgres")
            require(db_logs.count("10-create-application-role.sh") == 1, "Initialization must run only once")
            require(all(marker not in startup_logs + db_logs for marker in MARKERS), "Secret leaked to startup/DB logs")
            print("Same-volume restart preserves non-superuser role; init ran once; no secrets in startup/DB logs: PASS", flush=True)
            app = verify_database_operations(directory, env_file, project, compose, app, db, port)
            started = time.monotonic()
            run(*compose, "stop", "app", timeout=135)
            elapsed = time.monotonic() - started
            stopped = inspect(app)
            logs = run(*compose, "logs", "--no-color", "app")
            require(elapsed < 120 and stopped["State"]["ExitCode"] in (0, 143)
                    and not stopped["State"]["OOMKilled"], "App did not stop gracefully")
            require("Graceful shutdown complete" in logs and "Shutdown completed" in logs, "Spring shutdown evidence missing")
            print(f"SIGTERM: Spring graceful shutdown + datasource closure in {elapsed:.2f}s (<120s): PASS", flush=True)
            print("VULCAN requests=0; Telegram requests=0 (disabled runtime flags, synthetic empty DB; no provider calls).", flush=True)
        finally:
            # Unique generated project only. This never targets developer or production volumes.
            run(*compose, "down", "--volumes", "--remove-orphans", timeout=150)
    print("All container checks passed; disposable containers/network/database removed.", flush=True)
    git_status = run("git", "status", "--short", "--untracked-files=all")
    require(not any(line.endswith((".dump", ".sha256", ".meta", ".partial")) for line in git_status.splitlines()),
            "Generated database artifacts leaked into git status")
    print("Generated dump/checksum/metadata git-status audit: PASS", flush=True)


if __name__ == "__main__":
    main()
