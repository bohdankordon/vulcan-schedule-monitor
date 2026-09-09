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
import ssl
import ipaddress
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
    if not key.upper().startswith(("VULCAN_", "TELEGRAM_", "SPRING_", "POSTGRES_", "COMPOSE_", "EDGE_", "CADDY_", "SERVER_"))
    and key.upper() not in {
        "APP_IMAGE", "APP_PORT", "PUBLIC_BASE_URL", "JAVA_TOOL_OPTIONS",
        "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
    }
}
# SQL metacharacters exercise psql literal quoting as well as secret redaction.
MARKERS = ["synthetic-admin-" + secrets.token_hex(16),
           "synthetic-app-" + secrets.token_hex(16) + "';--",
           base64.b64encode(secrets.token_bytes(32)).decode()]
CADDY_IMAGE = "caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648"
CA_PATH = "/data/caddy/pki/authorities/local/root.crt"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
HTTPS = None


def request(url, headers=None, timeout=10):
    client = HTTPS if url.startswith("https:") else HTTP
    try:
        response = client.open(urllib.request.Request(url, headers=headers or {}), timeout=timeout)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, response.headers, response.read()


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def trust_caddy(container, directory):
    global HTTPS
    certificate = directory / "caddy-root.crt"
    # Export ONLY the public root, never any private key or the whole data volume.
    run("docker", "cp", container + ":" + CA_PATH, str(certificate))
    context = ssl.create_default_context(cafile=str(certificate))
    HTTPS = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                       urllib.request.HTTPSHandler(context=context))
    return hashlib.sha256(certificate.read_bytes()).hexdigest()



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
        code, _, body = request(f"https://localhost:{port}/actuator/health{path}", timeout=40)
        if code in (200, 503):
            expected = {"status": "UP" if code == 200 else "DOWN"}
            if not path:
                expected["groups"] = ["liveness", "readiness"]
            require(json.loads(body) == expected, "Health must disclose only status/public group names")
        return code
    except (OSError, urllib.error.URLError):
        return 0


def validate_config(config):
    app, db = config["services"]["app"], config["services"]["postgres"]
    caddy = config["services"]["caddy"]
    require(set(config["services"]) == {"caddy", "app", "postgres"}, "Unexpected service")
    require(not db.get("ports"), "PostgreSQL must not publish ports")
    require(not app.get("ports"), "Spring must not publish any host port")
    require(caddy["image"] == CADDY_IMAGE, "Pinned Caddy version/index drift")
    ports = caddy["ports"]
    require(len(ports) == 3 and all(p["host_ip"] == "127.0.0.1" for p in ports), "Edge loopback binding")
    tls_port = 8443
    require({(p["target"], p["protocol"]) for p in ports}
            == {(8080, "tcp"), (tls_port, "tcp"), (tls_port, "udp")}, "Unexpected edge listeners")
    require(set(config["networks"]) == {"edge", "backend"}
            and config["networks"]["backend"]["internal"]
            and not config["networks"]["edge"].get("internal"), "Network boundary")
    require(set(caddy["networks"]) == {"edge"} and set(app["networks"]) == {"edge", "backend"}
            and set(db["networks"]) == {"backend"}
            and app["networks"]["edge"]["gw_priority"] == 1, "Network membership/outbound gateway")
    require(app["environment"]["SERVER_FORWARD_HEADERS_STRATEGY"] == "FRAMEWORK", "Production forwarding")
    require(set(caddy["environment"]) == {"CADDY_SITE_ADDRESS", "EDGE_HTTPS_PORT"}, "Caddy secret separation")
    require(caddy["read_only"] and caddy["cap_drop"] == ["ALL"]
            and "no-new-privileges:true" in caddy["security_opt"], "Caddy hardening")
    require(caddy["restart"] == "unless-stopped" and caddy["stop_grace_period"] == "30s"
            and caddy["depends_on"]["app"]["condition"] == "service_healthy", "Caddy lifecycle")
    require({(v["source"], v["target"]) for v in caddy["volumes"] if v["type"] == "volume"}
            == {("caddy_data", "/data"), ("caddy_config", "/config")}, "Caddy state persistence")
    binds = [v for v in caddy["volumes"] if v["type"] == "bind"]
    require(len(caddy["volumes"]) == 3 and len(binds) == 1 and binds[0]["read_only"]
            and Path(binds[0]["source"]).resolve() == (ROOT / "docker/caddy/Caddyfile").resolve()
            and binds[0]["target"] == "/etc/caddy/Caddyfile", "Unexpected Caddy host mount")
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
    for service in (app, db, caddy):
        require(not service.get("privileged")
                and service.get("cap_add", []) == (["NET_BIND_SERVICE"] if service is caddy else []), "Excess privilege")
        require(service.get("network_mode") != "host" and service.get("ipc") != "host" and service.get("pid") != "host", "Host namespace")
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
    require(int(app.get("mem_limit", 0)) == 2 * 1024 * 1024 * 1024, "App container memory limit must be 2g")
    require(int(db.get("mem_limit", 0)) == 1 * 1024 * 1024 * 1024, "PostgreSQL container memory limit must be 1g")
    require(int(caddy.get("mem_limit", 0)) == 512 * 1024 * 1024, "Caddy container memory limit must be 512m")
    print("Compose topology, safe defaults, persistence, security and stop budget: PASS", flush=True)


def validate_production_ports(directory, quoted_app_password):
    prod_env = directory / "acer-server-production.env"
    prod_env.write_text(
        f"COMPOSE_PROJECT_NAME=vulcan-schedule-monitor-prod\n"
        f"POSTGRES_ADMIN_PASSWORD={MARKERS[0]}\n"
        f"POSTGRES_APP_PASSWORD='{quoted_app_password}'\n"
        f"VULCAN_MASTER_KEY={MARKERS[2]}\n"
        "EDGE_BIND_ADDRESS=0.0.0.0\n"
        "EDGE_HTTP_PORT=80\n"
        "EDGE_HTTPS_PORT=443\n"
        "CADDY_SITE_ADDRESS=vulcan-schedule-monitor.dns-dns.com\n"
        "PUBLIC_BASE_URL=https://vulcan-schedule-monitor.dns-dns.com\n"
        f"APP_IMAGE={IMAGE}\n",
        encoding="utf-8",
    )
    prod_config = json.loads(
        run(
            "docker",
            "compose",
            "--env-file",
            str(prod_env),
            "-f",
            str(ROOT / "compose.production.yml"),
            "config",
            "--format",
            "json",
        )
    )
    require(prod_config.get("name") == "vulcan-schedule-monitor-prod", "Canonical project name mismatch")
    caddy = prod_config["services"]["caddy"]
    app = prod_config["services"]["app"]
    db = prod_config["services"]["postgres"]

    require(not app.get("ports"), "Spring app must publish zero host ports in production")
    require(not db.get("ports"), "PostgreSQL must publish zero host ports in production")
    require(bool(caddy.get("ports")), "Caddy must publish host ports in production")
    require(int(app.get("mem_limit", 0)) == 2 * 1024 * 1024 * 1024, "App mem_limit mismatch")
    require(int(db.get("mem_limit", 0)) == 1 * 1024 * 1024 * 1024, "PostgreSQL mem_limit mismatch")
    require(int(caddy.get("mem_limit", 0)) == 512 * 1024 * 1024, "Caddy mem_limit mismatch")

    caddy_ports = {
        (p.get("host_ip", "0.0.0.0"), str(p["published"]), p["target"], p["protocol"])
        for p in caddy["ports"]
    }
    expected_ports = {
        ("0.0.0.0", "80", 8080, "tcp"),
        ("0.0.0.0", "443", 8443, "tcp"),
        ("0.0.0.0", "443", 8443, "udp"),
    }
    require(caddy_ports == expected_ports, f"Production edge host ports mismatch: {caddy_ports}")
    require(caddy["environment"]["CADDY_SITE_ADDRESS"] == "vulcan-schedule-monitor.dns-dns.com", "CADDY_SITE_ADDRESS not propagated")
    require(caddy["environment"]["EDGE_HTTPS_PORT"] == "443", "EDGE_HTTPS_PORT not propagated")
    require(app["environment"]["VULCAN_CONNECTION_PUBLICBASEURL"] == "https://vulcan-schedule-monitor.dns-dns.com", "PUBLIC_BASE_URL not propagated")
    print("Production-port configuration render (host 80/443 -> Caddy, zero app/db host ports): PASS", flush=True)


def validate_public_caddyfile():
    caddyfile_path = (ROOT / "docker/caddy/Caddyfile").resolve()
    caddy_cmd = [
        "docker",
        "run",
        "--rm",
        "-e",
        "CADDY_SITE_ADDRESS=vulcan-schedule-monitor.dns-dns.com",
        "-e",
        "EDGE_HTTPS_PORT=443",
        "-v",
        f"{caddyfile_path}:/etc/caddy/Caddyfile:ro",
        CADDY_IMAGE,
        "caddy",
        "adapt",
        "--config",
        "/etc/caddy/Caddyfile",
    ]
    adapted_raw = run(*caddy_cmd)
    adapted = json.loads(adapted_raw)
    http_apps = adapted["apps"]["http"]
    servers = http_apps["servers"]

    require(any(":8080" in s.get("listen", []) for s in servers.values()), "Caddy HTTP listener :8080 missing")
    require(any(":8443" in s.get("listen", []) for s in servers.values()), "Caddy HTTPS listener :8443 missing")

    https_server = next(s for s in servers.values() if ":8443" in s.get("listen", []))
    routes_json = json.dumps(https_server)

    require('"delete":["Forwarded"]' in routes_json or '"delete": ["Forwarded"]' in routes_json, "Forwarded header deletion missing")
    require('"delete":["X-Forwarded-*"]' in routes_json or '"delete": ["X-Forwarded-*"]' in routes_json, "X-Forwarded-* deletion missing")
    require('"dial":"app:8080"' in routes_json or '"dial": "app:8080"' in routes_json, "reverse_proxy app:8080 upstream missing")
    require("logs" not in https_server and "access_logger" not in routes_json, "Access logging must remain disabled")

    http_server = next(s for s in servers.values() if ":8080" in s.get("listen", []))
    http_routes_json = json.dumps(http_server)
    require("308" in http_routes_json and "vulcan-schedule-monitor.dns-dns.com:443" in http_routes_json, "HTTP to HTTPS redirect missing")

    print("Public hostname Caddy config adapt (Forwarded stripped, reverse_proxy app:8080, no access log): PASS", flush=True)


def validate_acer_server_preflight():
    script = ROOT / "scripts/deployment/acer-server-preflight.sh"
    require(script.is_file(), "scripts/deployment/acer-server-preflight.sh not found")

    bash = shutil.which("bash")
    if os.name == "nt":
        git = shutil.which("git")
        candidate = Path(git).resolve().parents[1] / "bin/bash.exe" if git else Path("missing")
        if candidate.is_file():
            bash = str(candidate)
    require(bash is not None, "Bash required for preflight verification")

    script_path = (ROOT / "scripts/deployment/acer-server-preflight.sh").as_posix()

    # Test 1: Wrong hostname failure
    res_host = subprocess.run(
        [bash, script_path, "--expected-hostname", "definitely-not-acer-server-98765"],
        cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=30
    )
    require(res_host.returncode != 0 and "Hostname mismatch" in res_host.stdout + res_host.stderr,
            "Preflight must fail on mismatched hostname")

    # Test 2: Unresolvable DDNS domain failure
    current_host = socket.gethostname()
    res_ddns = subprocess.run(
        [bash, script_path, "--expected-hostname", current_host, "--expected-domain", "nonexistent-domain-xyz987654321.invalid"],
        cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=30
    )
    require(res_ddns.returncode != 0 and "Unable to resolve DDNS domain" in res_ddns.stdout + res_ddns.stderr,
            "Preflight must fail on unresolvable DDNS domain")

    # Test 3: Missing Docker failure
    res_docker = subprocess.run(
        [bash, script_path, "--docker-bin", "nonexistent-docker-binary-xyz98765"],
        cwd=ROOT, env=ENV, capture_output=True, text=True, timeout=30
    )
    require(res_docker.returncode != 0 and "Docker" in res_docker.stdout + res_docker.stderr,
            "Preflight must fail when Docker is absent")

    print("Preflight script validation (wrong hostname, missing DDNS, missing Docker rejection): PASS", flush=True)


def validate_deployment_contract():
    # 1. Check systemd service unit defines canonical project
    svc_unit = (ROOT / "deploy/acer-server/systemd/vulcan-schedule-monitor-backup.service").read_text(encoding="utf-8")
    require("--project-name vulcan-schedule-monitor-prod" in svc_unit, "Backup service unit must specify canonical project name")
    require("--output-dir /srv/vulcan-schedule-monitor/backups" in svc_unit, "Backup service unit must specify /srv backup directory")

    # 2. Check example env has COMPOSE_PROJECT_NAME and memory policy
    env_example = (ROOT / "deploy/acer-server/.env.production.example").read_text(encoding="utf-8")
    require("COMPOSE_PROJECT_NAME=vulcan-schedule-monitor-prod" in env_example, ".env.production.example missing COMPOSE_PROJECT_NAME")
    require("JAVA_TOOL_OPTIONS=-XX:InitialRAMPercentage=20.0 -XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError" in env_example,
            ".env.production.example missing safe JVM memory containment")

    # 3. Check memory limits in compose.production.yml
    compose_prod = (ROOT / "compose.production.yml").read_text(encoding="utf-8")
    require("mem_limit: 2g" in compose_prod, "compose.production.yml app mem_limit must be 2g")
    require("mem_limit: 1g" in compose_prod, "compose.production.yml postgres mem_limit must be 1g")
    require("mem_limit: 512m" in compose_prod, "compose.production.yml caddy mem_limit must be 512m")

    # 4. Check docs/acer-server-deployment.md for exact restore contract, project identity, and volume naming
    runbook = (ROOT / "docs/acer-server-deployment.md").read_text(encoding="utf-8")
    require("COMPOSE_PROJECT_NAME=vulcan-schedule-monitor-prod" in runbook, "Runbook missing canonical project name")
    require("sudo docker compose --project-name vulcan-schedule-monitor-prod" in runbook, "Runbook must use canonical project in compose commands")
    require("vulcan-schedule-monitor-prod_postgres_data" in runbook, "Runbook must reference canonical volume name")
    require("compose.yaml" in runbook and "compose.yml stop" not in runbook, "Runbook must reference temporary edge compose.yaml")

    # 5. Check staged startup dependency ordering (Step 10)
    require("up -d postgres app" in runbook, "Runbook Step 10 must use staged startup 'up -d postgres app'")
    require("up -d --no-deps postgres app" not in runbook, "Runbook Step 10 must NOT bypass dependency ordering with --no-deps")

    # 6. Check Level 2 recovery flow: distinguishes pre-baseline vs post-baseline, forbids down before restore
    require("Case A: Failure Before a Baseline Backup Exists" in runbook, "Runbook must document failure before baseline backup exists")
    require("Case B: Failure After a Valid Baseline" in runbook, "Runbook must document failure after baseline backup exists")
    require("Do NOT run `docker compose down`" in runbook, "Runbook must warn against running docker compose down before restore")
    case_b = runbook.split("Case B:")[1]
    down_idx = case_b.find("compose.production.yml down")
    restore_idx = case_b.find("scripts/database/restore.sh")
    require(down_idx == -1 or down_idx > restore_idx, "Runbook must NOT instruct docker compose down before restore.sh in Case B")

    # 7. Check provider disablement separation and app recreation in Case B
    require("up -d --no-deps --force-recreate app" in case_b,
            "Case B must document app container recreation with up -d --no-deps --force-recreate app")
    operator_config = case_b.split("Operator Configuration")[1].split("Effective App Container Environment")[0]
    require("VULCAN_CONNECTION_ENABLED=false" in operator_config
            and "VULCAN_MONITORING_ENABLED=false" in operator_config
            and "TELEGRAM_ENABLED=false" in operator_config,
            "Case B operator config (.env.production) must specify TELEGRAM_ENABLED=false")
    require("TELEGRAM_BOT_ENABLED=false" not in operator_config,
            "Case B operator config must NOT instruct setting TELEGRAM_BOT_ENABLED in .env.production")

    effective_env = case_b.split("Effective App Container Environment")[1].split("Before restore")[0]
    require("VULCAN_CONNECTION_ENABLED=false" in effective_env
            and "VULCAN_MONITORING_ENABLED=false" in effective_env
            and "TELEGRAM_BOT_ENABLED=false" in effective_env,
            "Case B effective container environment must specify TELEGRAM_BOT_ENABLED=false")

    # 8. Check restore command shape and provider safety invariants
    require("--archive" in runbook and "--confirm schedule_monitor" in runbook, "Runbook restore missing required arguments")
    require("--project-name vulcan-schedule-monitor-prod" in runbook, "Runbook restore missing canonical project name")
    print("Deployment contract, canonical project name, memory limits and restore CLI verification: PASS", flush=True)


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
    override = directory / "provider-fixture.yml"
    for provider in ("TELEGRAM_BOT_ENABLED", "VULCAN_CONNECTION_ENABLED", "VULCAN_MONITORING_ENABLED"):
        for value in ("true", None, "FALSE"):
            override.write_text("services:\n  app:\n    networks: !reset {}\n    <<: " + json.dumps({
                "environment": {provider: value}, "entrypoint": ["sleep", "infinity"],
                "network_mode": "none", "restart": "no", "healthcheck": {"disable": True},
            }) + "\n", encoding="utf-8")
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
    wait_for("Provider-disabled app recreation readiness HTTPS 200", lambda: status(port, "/readiness") == 200)
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


def require_same_process(container, before, message):
    after = inspect(container)
    require(after["State"]["Running"]
            and after["State"]["StartedAt"] == before["State"]["StartedAt"]
            and after["RestartCount"] == before["RestartCount"], message)


def verify_edge(directory, compose, app, db, caddy, port, http_port):
    version = run("docker", "exec", caddy, "caddy", "version")
    require(version.split()[0] == "v2.11.4", "Runtime Caddy version drift")
    run("docker", "exec", caddy, "caddy", "validate", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile")
    adapted = json.loads(run("docker", "exec", caddy, "caddy", "adapt", "--config", "/etc/caddy/Caddyfile"))
    require(adapted["admin"]["disabled"], "Admin API must be disabled")
    server = next(server for server in adapted["apps"]["http"]["servers"].values()
                  if server["listen"] == [":8443"])
    handlers = server["routes"][0]["handle"][0]["routes"][0]["handle"][0]["routes"][0]["handle"]
    require(handlers[0]["handler"] == "headers" and handlers[0]["request"]["delete"] == ["Forwarded"]
            and handlers[1]["handler"] == "headers" and handlers[1]["request"]["delete"] == ["X-Forwarded-*"]
            and handlers[2]["handler"] == "reverse_proxy"
            and handlers[2]["upstreams"] == [{"dial": "app:8080"}], "Sanitization must precede proxy generation")
    require("logs" not in server and "trusted_proxies" not in json.dumps(adapted), "Unsafe request logging/proxy trust")
    runtime = inspect(caddy)
    require(not inspect(app)["HostConfig"]["PortBindings"]
            and not inspect(db)["HostConfig"]["PortBindings"], "Direct app/DB host port")
    require(all(not bindings for bindings in inspect(app)["NetworkSettings"]["Ports"].values())
            and all(not bindings for bindings in inspect(db)["NetworkSettings"]["Ports"].values()), "Runtime publication")
    require({k for k, v in runtime["HostConfig"]["PortBindings"].items() if v}
            == {"8080/tcp", "8443/tcp", "8443/udp"}
            and all(v["HostIp"] == "127.0.0.1" for bindings in runtime["HostConfig"]["PortBindings"].values()
                    for v in bindings), "Only loopback edge ports may be published")
    edge_networks = runtime["NetworkSettings"]["Networks"]
    db_networks = inspect(db)["NetworkSettings"]["Networks"]
    app_networks = inspect(app)["NetworkSettings"]["Networks"]
    require(len(edge_networks) == len(db_networks) == 1 and not set(edge_networks) & set(db_networks)
            and set(app_networks) == set(edge_networks) | set(db_networks), "Runtime network segmentation")
    edge_name = next(iter(edge_networks))
    gateway = app_networks[edge_name]["Gateway"]
    routes = run("docker", "exec", app, "cat", "/proc/net/route")
    gateway_hex = socket.inet_aton(gateway)[::-1].hex().upper()
    require(any(row.split()[1:3] == ["00000000", gateway_hex] for row in routes.splitlines()[1:]),
            "App default egress route must use edge gateway (no provider request)")
    names = {item.split("=", 1)[0] for item in runtime["Config"]["Env"]}
    require(not any(name.startswith(("POSTGRES_", "VULCAN_", "TELEGRAM_", "SPRING_")) for name in names),
            "Caddy contains app/database secret names")
    require(not any(marker in json.dumps(runtime["Config"]) for marker in MARKERS), "Caddy secret value leak")
    host = runtime["HostConfig"]
    require(host["ReadonlyRootfs"] and host["CapDrop"] == ["ALL"] and [cap.removeprefix("CAP_") for cap in host["CapAdd"]] == ["NET_BIND_SERVICE"]
            and "no-new-privileges:true" in host["SecurityOpt"] and not host["Privileged"]
            and host["PidMode"] != "host" and host["NetworkMode"] != "host", "Runtime edge hardening")
    require({m["Destination"] for m in runtime["Mounts"] if m["RW"]} == {"/data", "/config"}
            and len(runtime["Mounts"]) == 3, "Unexpected writable/host mounts")
    trust_caddy(caddy, directory)
    wait_for("Trusted local CA chain + localhost hostname TLS handshake / readiness", lambda: status(port, "/readiness") == 200)
    code, headers, _ = request(f"http://localhost:{http_port}/actuator/health/readiness?synthetic=1")
    require(code == 308 and headers["Location"] == f"https://localhost:{port}/actuator/health/readiness?synthetic=1",
            f"HTTP redirect mismatch: status={code}, location={headers.get('Location')}")
    require(request(f"https://localhost:{port}/unrelated-route")[0] == 403, "Spring route authorization bypass")
    malicious = {
        "Forwarded": "for=203.0.113.7;proto=http;host=attacker.invalid",
        "X-Forwarded-For": "203.0.113.7", "X-Forwarded-Proto": "http",
        "X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Port": "80",
        "X-Forwarded-Prefix": "/attacker", "X-Forwarded-Unknown": "attacker",
    }
    code, headers, _ = request(f"https://localhost:{port}/actuator/health/liveness", malicious)
    require(code == 200 and "max-age=" in headers.get("Strict-Transport-Security", ""), "Spoofing changed Spring secure semantics")
    print("Caddy 2.11.4/config; admin off; no direct app/DB ports; networks/egress; hardening/secrets; redirect/authorization/HSTS: PASS", flush=True)
    verify_header_boundary(directory, compose, app, db, caddy, port, malicious)


def verify_header_boundary(directory, compose, app, db, caddy, port, malicious):
    # Replace ONLY the disposable app with the existing small smoke image. This
    # exercises the byte-identical production Caddyfile and app:8080 upstream.
    override = directory / "header-echo.json"
    override.write_text(json.dumps({"services": {"app": {
        "image": SMOKE_IMAGE, "entrypoint": ["java", "-cp", "/smoke", "HeaderEcho"],
        "healthcheck": {"disable": True},
    }}}), encoding="utf-8")
    db_before, edge_before = inspect(db), inspect(caddy)
    try:
        run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--force-recreate", "--no-build", "app")

        def sanitized():
            try:
                code, _, body = request(f"https://localhost:{port}/", malicious)
            except (OSError, urllib.error.URLError):
                return False
            if code != 200:
                return False
            received = dict(line.split(": ", 1) for line in body.decode().splitlines())
            require("forwarded" not in received, "Attacker Forwarded reached upstream")
            require({name for name in received if name.startswith("x-forwarded-")}
                    == {"x-forwarded-for", "x-forwarded-proto", "x-forwarded-host"}, "Attacker forwarding extension reached upstream")
            require(received["x-forwarded-proto"] == "https"
                    and received["x-forwarded-host"] == f"localhost:{port}"
                    and received["host"] == f"localhost:{port}", "Proxy scheme/host mismatch")
            client = ipaddress.ip_address(received["x-forwarded-for"])
            require(str(client) != "203.0.113.7" and b"attacker" not in body, "Attacker client chain preserved")
            return True

        wait_for("Same production Caddyfile removes every spoofed forwarding header; safe HTTPS/host/client reach upstream", sanitized)
    finally:
        run(*compose, "up", "-d", "--no-deps", "--force-recreate", "--no-build", "app")
        wait_for("Provider-disabled Spring restored after header echo", lambda: status(port, "/readiness") == 200)
    require_same_process(db, db_before, "Header fixture changed DB")
    require_same_process(caddy, edge_before, "Header fixture changed Caddy")


def verify_connect(directory, compose, app, db, caddy, port):
    override = directory / "connect-fixture.json"
    override.write_text(json.dumps({"services": {"app": {"environment": {
        "VULCAN_CONNECTION_ENABLED": "true", "VULCAN_MONITORING_ENABLED": "false",
        "TELEGRAM_BOT_ENABLED": "false", "VULCAN_CONNECTION_PUBLICBASEURL": f"https://localhost:{port}",
    }}}}), encoding="utf-8")
    db_before, edge_before = inspect(db), inspect(caddy)
    try:
        run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--force-recreate", "--no-build", "app")
        wait_for("Synthetic connection-only app ready", lambda: status(port, "/readiness") == 200)
        code, headers, body = request(f"https://localhost:{port}/connect")
        require(code == 200 and b"<!doctype html>" in body.lower(), "Connect page over HTTPS")
        require("no-store" in headers.get("Cache-Control", "")
                and headers.get("Referrer-Policy") == "no-referrer"
                and headers.get("Content-Security-Policy")
                and headers.get("X-Content-Type-Options") == "nosniff"
                and headers.get("X-Frame-Options") == "DENY"
                and headers.get("Strict-Transport-Security"), "Connect security headers")
        cookie = headers.get("Set-Cookie", "")
        require(all(part in cookie for part in ("Secure", "HttpOnly", "SameSite=Strict", "Max-Age=0", "Path=/connect")),
                "Connect cookie clearing security")
        marker = "synthetic-edge-" + uuid.uuid4().hex
        code, headers, _ = request(f"https://localhost:{port}/connect/{marker}")
        require(code == 303 and headers["Location"].endswith("/connect?invalid"), "Synthetic token path")
        require(marker not in run(*compose, "logs", "--no-color", "caddy"), "Connect marker in Caddy logs")
        print("Connect HTTPS GET/security/cookie clearing + synthetic token redirect/log privacy; no credential POST: PASS", flush=True)
    finally:
        run(*compose, "up", "-d", "--no-deps", "--force-recreate", "--no-build", "app")
        wait_for("All-provider-disabled HTTPS readiness before recovery suite", lambda: status(port, "/readiness") == 200)
    app = run(*compose, "ps", "--quiet", "app")
    require(all(k + "=false" in inspect(app)["Config"]["Env"] for k in (
        "VULCAN_CONNECTION_ENABLED", "VULCAN_MONITORING_ENABLED", "TELEGRAM_BOT_ENABLED")), "Provider reset failed")
    require_same_process(db, db_before, "App-only recreation changed DB")
    require_same_process(caddy, edge_before, "App-only recreation changed Caddy")
    return app


def verify_caddy_restart(directory, compose, caddy, port):
    fingerprint = trust_caddy(caddy, directory)
    mounts = {m["Destination"]: m for m in inspect(caddy)["Mounts"]}
    started = time.monotonic()
    run(*compose, "stop", "caddy", timeout=40)
    stopped = inspect(caddy)
    require(time.monotonic() - started < 30 and stopped["State"]["ExitCode"] == 0
            and not stopped["State"]["OOMKilled"], "Caddy SIGTERM/graceful stop failed")
    require('"signal":"SIGTERM"' in run(*compose, "logs", "--no-color", "caddy"), "Caddy signal evidence missing")
    run(*compose, "start", "caddy")
    wait_for("Caddy clean stop/start with original CA trust", lambda: status(port, "/readiness") == 200)
    run(*compose, "up", "-d", "--no-deps", "--force-recreate", "--no-build", "caddy")
    recreated = run(*compose, "ps", "--quiet", "caddy")
    require(recreated != caddy and {m["Destination"]: m for m in inspect(recreated)["Mounts"]} == mounts, "Caddy recreation/state mounts")
    wait_for("Caddy recreation TLS verified with ORIGINAL trusted CA", lambda: status(port, "/readiness") == 200)
    require(trust_caddy(recreated, directory) == fingerprint, "Local CA identity changed")
    print("Caddy graceful SIGTERM, restart/recreation, persistent public CA fingerprint and trusted TLS: PASS", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use an already built production image")
    args = parser.parse_args()
    (ROOT / "target").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="container-smoke-", dir=ROOT / "target") as directory:
        directory = Path(directory)
        env_file = directory / "synthetic.env"
        # Distinct free loopback ports; Compose fails safely on a concurrent claim.
        port, http_port = free_port(), free_port()
        while http_port == port:
            http_port = free_port()
        quoted_app_password = MARKERS[1].replace("'", "\\'")
        env_file.write_text(f"POSTGRES_ADMIN_PASSWORD={MARKERS[0]}\n"
                            f"POSTGRES_APP_PASSWORD='{quoted_app_password}'\nVULCAN_MASTER_KEY={MARKERS[2]}\n"
                            f"EDGE_HTTPS_PORT={port}\nEDGE_HTTP_PORT={http_port}\n"
                            "CADDY_SITE_ADDRESS=localhost\n"
                            f"APP_IMAGE={IMAGE}\n", encoding="utf-8")
        project = "vsm-smoke-" + uuid.uuid4().hex[:12]
        base = ["docker", "compose", "--project-name", project, "--env-file", str(env_file),
                "-f", str(ROOT / "compose.production.yml")]
        compose = base
        config = json.loads(run(*base, "config", "--format", "json"))
        validate_config(config)
        default_env = directory / "defaults.env"
        default_env.write_text("\n".join(line for line in env_file.read_text().splitlines()
                                          if not line.startswith(("EDGE_", "CADDY_"))), encoding="utf-8")
        defaults = json.loads(run("docker", "compose", "--env-file", str(default_env),
                                  "-f", str(ROOT / "compose.production.yml"), "config", "--format", "json"))
        validate_config(defaults)
        default_edge = defaults["services"]["caddy"]
        require(default_edge["environment"] == {"CADDY_SITE_ADDRESS": "localhost",
                                                "EDGE_HTTPS_PORT": "8443"}
                and {(p["published"], p["target"], p["protocol"]) for p in default_edge["ports"]}
                == {("8080", 8080, "tcp"), ("8443", 8443, "tcp"), ("8443", 8443, "udp")},
                "Safe production edge defaults drifted")
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
        validate_production_ports(directory, quoted_app_password)
        validate_public_caddyfile()
        validate_acer_server_preflight()
        validate_deployment_contract()
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
            caddy = run(*compose, "ps", "--quiet", "caddy")
            startup_logs = run(*compose, "logs", "--no-color", "app")
            verify_edge(directory, compose, app, db, caddy, port, http_port)
            app = run(*compose, "ps", "--quiet", "app")
            for path in ("", "/liveness", "/readiness"):
                require(status(port, path) == 200, "Healthy endpoint failed: " + path)
            print("App + PostgreSQL healthy; root/liveness/readiness HTTPS 200: PASS", flush=True)
            require("Successfully applied" in startup_logs and "Initialized JPA EntityManagerFactory" in startup_logs,
                    "Flyway migration/JPA initialization evidence missing")
            verify_application_role(db)
            application_sql(db, "CREATE DATABASE forbidden_smoke_database;", denied=True)
            application_sql(db, "CREATE ROLE forbidden_smoke_role;", denied=True)
            print("Application CREATE DATABASE and CREATE ROLE: denied (SQLSTATE 42501): PASS", flush=True)
            db_volume = next(m["Name"] for m in inspect(db)["Mounts"] if m["Type"] == "volume")
            before = inspect(app)
            edge_before = inspect(caddy)
            run(*compose, "stop", "postgres")
            wait_for("DB outage readiness HTTPS 503", lambda: status(port, "/readiness") == 503)
            require(status(port, "/liveness") == 200, "DB outage changed liveness")
            wait_for("Docker liveness healthcheck continues during DB outage",
                     lambda: bool(inspect(app).get("State", {}).get("Health", {}).get("Log"))
                     and (
                         not before.get("State", {}).get("Health", {}).get("Log")
                         or inspect(app)["State"]["Health"]["Log"][-1]["Start"]
                         > before["State"]["Health"]["Log"][-1]["Start"]
                     ))
            after = inspect(app)
            require(after["State"]["Health"]["Status"] == "healthy"
                    and after["RestartCount"] == before["RestartCount"] == 0
                    and after["State"]["StartedAt"] == before["State"]["StartedAt"], "App restarted during DB outage")
            print("DB outage liveness HTTPS 200; container healthy; restart count=0: PASS", flush=True)
            run(*compose, "start", "postgres")
            wait_for("DB restart readiness HTTPS 200", lambda: status(port, "/readiness") == 200)
            require(db_volume == next(m["Name"] for m in inspect(db)["Mounts"] if m["Type"] == "volume"),
                    "PostgreSQL restart must reuse its named volume")
            require_same_process(caddy, edge_before, "DB outage restarted Caddy")
            verify_application_role(db)
            db_logs = run(*compose, "logs", "--no-color", "postgres")
            require(db_logs.count("10-create-application-role.sh") == 1, "Initialization must run only once")
            require(all(marker not in startup_logs + db_logs for marker in MARKERS), "Secret leaked to startup/DB logs")
            print("Same-volume restart preserves non-superuser role; init ran once; no secrets in startup/DB logs: PASS", flush=True)
            app = verify_connect(directory, compose, app, db, caddy, port)
            edge_before = inspect(caddy)
            app = verify_database_operations(directory, env_file, project, compose, app, db, port)
            require_same_process(caddy, edge_before, "Recovery recreated/restarted Caddy")
            require({m["Destination"]: m for m in inspect(caddy)["Mounts"]}
                    == {m["Destination"]: m for m in edge_before["Mounts"]}, "Recovery changed Caddy state mounts")
            require(status(port, "/readiness") == 200, "HTTPS readiness after recovery")
            verify_caddy_restart(directory, compose, caddy, port)
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
    print("All container checks passed; disposable containers/networks/database/Caddy volumes and public CA removed.", flush=True)
    git_status = run("git", "status", "--short", "--untracked-files=all")
    require(not any(line.endswith((".dump", ".sha256", ".meta", ".partial")) for line in git_status.splitlines()),
            "Generated database artifacts leaked into git status")
    print("Generated dump/checksum/metadata git-status audit: PASS", flush=True)


if __name__ == "__main__":
    main()
