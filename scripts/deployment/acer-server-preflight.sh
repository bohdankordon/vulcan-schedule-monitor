#!/usr/bin/env bash
# Read-only deployment preflight inspection for acer-server.
# Never modifies server state, never prints secrets, and produces structured status output.
set -Eeuo pipefail

EXPECTED_HOSTNAME="${EXPECTED_HOSTNAME:-acer-server}"
EXPECTED_DOMAIN="${EXPECTED_DOMAIN:-vulcan-schedule-monitor.dns-dns.com}"
EXPECTED_DEPLOY_DIR="${EXPECTED_DEPLOY_DIR:-/opt/vulcan-schedule-monitor}"
EXPECTED_BACKUP_DIR="${EXPECTED_BACKUP_DIR:-/srv/vulcan-schedule-monitor/backups}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
MIN_RAM_AVAILABLE_MB=2048
MIN_DISK_FREE_GB=10

usage() {
    cat <<'EOF'
Usage: bash scripts/deployment/acer-server-preflight.sh [OPTIONS]

Options:
  --expected-hostname HOSTNAME   Expected system hostname (default: acer-server)
  --expected-domain DOMAIN       Expected public domain (default: vulcan-schedule-monitor.dns-dns.com)
  --expected-deploy-dir DIR      Expected deployment directory (default: /opt/vulcan-schedule-monitor)
  --expected-backup-dir DIR      Expected backup directory (default: /srv/vulcan-schedule-monitor/backups)
  --docker-bin BIN               Docker CLI binary name or path (default: docker)
  -h, --help                     Show this help message
EOF
}

while (($#)); do
    case "$1" in
        --expected-hostname) EXPECTED_HOSTNAME="$2"; shift 2 ;;
        --expected-domain) EXPECTED_DOMAIN="$2"; shift 2 ;;
        --expected-deploy-dir) EXPECTED_DEPLOY_DIR="$2"; shift 2 ;;
        --expected-backup-dir) EXPECTED_BACKUP_DIR="$2"; shift 2 ;;
        --docker-bin) DOCKER_BIN="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
done

FAILURES=0
WARNINGS=0

pass() { printf '[PASS] %s\n' "$*"; }
warn() { printf '[WARN] %s\n' "$*"; WARNINGS=$((WARNINGS + 1)); }
fail() { printf '[FAIL] %s\n' "$*"; FAILURES=$((FAILURES + 1)); }
info() { printf '[INFO] %s\n' "$*"; }

printf '=== acer-server Deployment Preflight ===\n'

# 1. Hostname verification
CURRENT_HOSTNAME=$(hostname 2>/dev/null || uname -n 2>/dev/null || cat /etc/hostname 2>/dev/null || echo "unknown")
if [[ "$CURRENT_HOSTNAME" == "$EXPECTED_HOSTNAME" ]]; then
    pass "Hostname matches expected: $CURRENT_HOSTNAME"
else
    fail "Hostname mismatch: expected '$EXPECTED_HOSTNAME', got '$CURRENT_HOSTNAME'"
fi

# 2. Docker CLI and Daemon accessibility
DOCKER_CMD=""
if ! command -v "$DOCKER_BIN" >/dev/null 2>&1; then
    fail "Docker CLI binary '$DOCKER_BIN' not found in PATH"
else
    # Check if docker daemon is reachable directly or with sudo -n
    if "$DOCKER_BIN" info >/dev/null 2>&1; then
        DOCKER_CMD="$DOCKER_BIN"
        pass "Docker daemon reachable directly"
    elif command -v sudo >/dev/null 2>&1 && sudo -n "$DOCKER_BIN" info >/dev/null 2>&1; then
        DOCKER_CMD="sudo -n $DOCKER_BIN"
        pass "Docker daemon reachable via sudo -n"
    else
        fail "Cannot connect to Docker daemon (tested '$DOCKER_BIN info' and 'sudo -n $DOCKER_BIN info')"
    fi
fi

if [[ -n "$DOCKER_CMD" ]] && $DOCKER_CMD info >/dev/null 2>&1; then
    DOCKER_SERVER_VER=$($DOCKER_CMD version --format '{{.Server.Version}}' 2>/dev/null || echo "unknown")
    pass "Docker Server Engine version: $DOCKER_SERVER_VER"

    if $DOCKER_CMD compose version >/dev/null 2>&1; then
        COMPOSE_VER=$($DOCKER_CMD compose version --short 2>/dev/null || $DOCKER_CMD compose version)
        pass "Docker Compose plugin available: $COMPOSE_VER"
    else
        fail "Docker Compose plugin not available"
    fi
fi

# 3. System Memory / RAM Check
if [[ -f /proc/meminfo ]]; then
    MEM_AVAIL_KB=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
    MEM_TOTAL_KB=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
    MEM_AVAIL_MB=$((MEM_AVAIL_KB / 1024))
    MEM_TOTAL_MB=$((MEM_TOTAL_KB / 1024))
    if (( MEM_AVAIL_MB >= MIN_RAM_AVAILABLE_MB )); then
        pass "RAM Available: ${MEM_AVAIL_MB} MiB / ${MEM_TOTAL_MB} MiB (meets >= ${MIN_RAM_AVAILABLE_MB} MiB threshold)"
    else
        warn "RAM Available: ${MEM_AVAIL_MB} MiB / ${MEM_TOTAL_MB} MiB (low: threshold is ${MIN_RAM_AVAILABLE_MB} MiB)"
    fi
else
    warn "Cannot read /proc/meminfo to verify available RAM"
fi

# 4. Root Disk Free Space Check
if command -v df >/dev/null 2>&1; then
    DISK_AVAIL_GB=$( (df -BG / 2>/dev/null || true) | awk 'NR==2 {gsub("G",""); print $4}')
    if [[ -n "$DISK_AVAIL_GB" ]] && (( DISK_AVAIL_GB >= MIN_DISK_FREE_GB )); then
        pass "Root filesystem available: ${DISK_AVAIL_GB} GiB (meets >= ${MIN_DISK_FREE_GB} GiB threshold)"
    elif [[ -n "$DISK_AVAIL_GB" ]]; then
        fail "Root filesystem available: ${DISK_AVAIL_GB} GiB (insufficient: requires >= ${MIN_DISK_FREE_GB} GiB)"
    else
        warn "Unable to parse root disk free space"
    fi
fi

# 5. Public DDNS Resolution Check
RESOLVED_IPV4=""
if command -v getent >/dev/null 2>&1; then
    RESOLVED_IPV4=$( (getent ahostsv4 "$EXPECTED_DOMAIN" 2>/dev/null || true) | awk '{print $1; exit}')
fi
if [[ -z "$RESOLVED_IPV4" ]] && command -v resolvectl >/dev/null 2>&1; then
    RESOLVED_IPV4=$( (resolvectl query "$EXPECTED_DOMAIN" 2>/dev/null || true) | awk '/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/ {print $2; exit}')
fi
if [[ -z "$RESOLVED_IPV4" ]] && command -v dig >/dev/null 2>&1; then
    RESOLVED_IPV4=$( (dig +short A "$EXPECTED_DOMAIN" 2>/dev/null || true) | tail -n 1)
fi

if [[ -n "$RESOLVED_IPV4" ]]; then
    pass "DDNS domain '$EXPECTED_DOMAIN' resolves to IPv4: $RESOLVED_IPV4"
    # Check against public IP if curl is available
    if command -v curl >/dev/null 2>&1; then
        PUB_IP=$(curl -4 -s --max-time 5 https://api.ipify.org 2>/dev/null || true)
        if [[ -n "$PUB_IP" ]]; then
            if [[ "$PUB_IP" == "$RESOLVED_IPV4" ]]; then
                pass "Public IPv4 ($PUB_IP) matches DDNS record ($RESOLVED_IPV4)"
            else
                fail "Public IPv4 ($PUB_IP) does NOT match DDNS record ($RESOLVED_IPV4)"
            fi
        else
            info "Unable to query api.ipify.org; outbound public IPv4 check skipped"
        fi
    fi
else
    fail "Unable to resolve DDNS domain '$EXPECTED_DOMAIN' to an IPv4 address"
fi

# 6. UFW Firewall State
if command -v ufw >/dev/null 2>&1 || (command -v sudo >/dev/null 2>&1 && sudo -n command -v ufw >/dev/null 2>&1); then
    UFW_STATUS=$( (sudo -n ufw status 2>/dev/null || ufw status 2>/dev/null) | head -n 1 )
    if [[ "$UFW_STATUS" =~ [Aa]ctive ]]; then
        pass "UFW is active"
        UFW_RULES=$(sudo -n ufw status 2>/dev/null || ufw status 2>/dev/null || true)
        if echo "$UFW_RULES" | grep -qE '^80(/tcp)?\s+ALLOW'; then
            pass "UFW allows port 80/tcp"
        else
            fail "UFW does not allow port 80/tcp"
        fi
        if echo "$UFW_RULES" | grep -qE '^443(/tcp)?\s+ALLOW'; then
            pass "UFW allows port 443/tcp"
        else
            fail "UFW does not allow port 443/tcp"
        fi
    else
        warn "UFW is inactive or not reporting active state"
    fi
else
    info "UFW binary not installed or accessible; firewall check skipped"
fi

# 7. Ports 80 and 443 Listener and Container Assessment
CONTAINERS_RUNNING=""
if [[ -n "$DOCKER_CMD" ]] && $DOCKER_CMD info >/dev/null 2>&1; then
    CONTAINERS_RUNNING=$($DOCKER_CMD ps --format '{{.Names}} (Image: {{.Image}})' 2>/dev/null || true)
fi

PORT_80_LISTENER=""
PORT_443_LISTENER=""
if command -v ss >/dev/null 2>&1; then
    PORT_80_LISTENER=$( (sudo -n ss -lntup 'sport = :80' 2>/dev/null || ss -lntup 'sport = :80' 2>/dev/null) | awk 'NR>1 {print $0}' || true)
    PORT_443_LISTENER=$( (sudo -n ss -lntup 'sport = :443' 2>/dev/null || ss -lntup 'sport = :443' 2>/dev/null) | awk 'NR>1 {print $0}' || true)
fi

info "Inspecting current port 80/443 listener ownership:"
IS_TEMP_CADDY=false
IS_VULCAN_CADDY=false

if echo "$CONTAINERS_RUNNING" | grep -q "acer-server-caddy"; then
    IS_TEMP_CADDY=true
    pass "Temporary edge container 'acer-server-caddy' is running (Pre-deployment state)"
fi
if echo "$CONTAINERS_RUNNING" | grep -qE 'vulcan.*caddy'; then
    IS_VULCAN_CADDY=true
    pass "Vulcan bundled Caddy container is running (Post-cutover state)"
fi

if [[ -n "$PORT_80_LISTENER" || -n "$PORT_443_LISTENER" ]]; then
    if $IS_TEMP_CADDY; then
        pass "Ports 80/443 belong to temporary 'acer-server-caddy'; ready for staged transition"
    elif $IS_VULCAN_CADDY; then
        pass "Ports 80/443 belong to Vulcan bundled Caddy stack"
    else
        warn "Ports 80/443 are listening but owner is neither 'acer-server-caddy' nor Vulcan Caddy"
    fi
else
    info "Ports 80 and 443 have no current listeners (standby state)"
fi

# 8. Vulcan Deployment Directory & Project State
if [[ -d "$EXPECTED_DEPLOY_DIR" ]]; then
    info "Deployment directory '$EXPECTED_DEPLOY_DIR' exists"
    if [[ -f "$EXPECTED_DEPLOY_DIR/.env.production" ]]; then
        info "Environment file '$EXPECTED_DEPLOY_DIR/.env.production' is present"
    fi
else
    info "Deployment directory '$EXPECTED_DEPLOY_DIR' does not yet exist (normal prior to first deploy)"
fi

if [[ -d "$EXPECTED_BACKUP_DIR" ]]; then
    info "Backup directory '$EXPECTED_BACKUP_DIR' exists"
else
    info "Backup directory '$EXPECTED_BACKUP_DIR' does not yet exist (will be created during setup)"
fi

# Check for conflicting Vulcan production compose project
if [[ -n "$DOCKER_CMD" ]] && $DOCKER_CMD info >/dev/null 2>&1; then
    CONFLICTS=$($DOCKER_CMD ps --filter "label=com.docker.compose.project=vulcan-schedule-monitor-prod" --format '{{.Names}}' 2>/dev/null || true)
    if [[ -n "$CONFLICTS" ]]; then
        info "Running containers in project 'vulcan-schedule-monitor-prod': $CONFLICTS"
    else
        pass "No conflicting 'vulcan-schedule-monitor-prod' containers running"
    fi
fi

printf '\n=== Summary ===\n'
printf 'Failures: %d, Warnings: %d\n' "$FAILURES" "$WARNINGS"

if (( FAILURES > 0 )); then
    printf 'Preflight status: FAILED (resolve failed checks before deploying)\n'
    exit 1
else
    printf 'Preflight status: PASSED (host meets deployment prerequisites)\n'
    exit 0
fi
