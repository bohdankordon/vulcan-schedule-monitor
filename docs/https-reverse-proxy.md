# HTTPS reverse-proxy foundation

Production Compose now routes `host -> Caddy -> app:8080 -> postgres:5432`.
Only Caddy publishes host ports. Spring and PostgreSQL have no host bindings,
including on loopback. The separate developer runner retains direct HTTP and
does not enable forwarded-header trust.

## Image, ports and automatic HTTPS

The official `caddy:2.11.4-alpine` image is pinned to OCI index
`sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648`.
The index includes Linux amd64, arm/v6, arm/v7, arm64/v8, ppc64le, riscv64 and
s390x manifests; no single-architecture digest or platform override is imposed.
The application stack is tested on Linux amd64. The harness checks the actual
`caddy version` binary as well as the exact Compose reference.

| Setting | Local default | Meaning |
| --- | --- | --- |
| `EDGE_BIND_ADDRESS` | `127.0.0.1` | Host interface published by Docker |
| `EDGE_HTTP_PORT` | `8080` | Host HTTP port; redirects to HTTPS |
| `EDGE_HTTPS_PORT` | `8443` | Host HTTPS port, TCP and optional HTTP/3 UDP |
| `CADDY_SITE_ADDRESS` | `localhost` | Hostname only, without scheme or port |
| `PUBLIC_BASE_URL` | empty | Application's configured external connect-link origin |

HTTP listens on container port 8080; HTTPS listens on 8443. Caddy's `http_port`
and `https_port` options support forwarding future public host ports 80/443 to
these high internal ports; ACME clients still reach the standard public ports.
See the [official port options](https://caddyserver.com/docs/caddyfile/options#http-port).
Host ports may change independently of the fixed container listeners. The
harness uses unique available host ports. The hostname-only site setting avoids
confusing an external port with a Caddy listener port; `EDGE_HTTPS_PORT` also
supplies the canonical HTTP redirect destination.

The tracked Caddyfile uses [automatic HTTPS](https://caddyserver.com/docs/automatic-https):
localhost gets Caddy's internal CA; a future real hostname selects normal public
certificate automation. No `tls internal`, public ACME smoke, fake public site,
DNS plugin, or custom Caddy build is used. An explicit HTTP redirect uses the
configured site authority and preserves path/query: Caddy otherwise normalizes
its global internal HTTPS port to public 443, which is unsuitable for localhost
high host ports. The HTTP site matches the configured hostname explicitly, so
its route precedes the automatic fallback redirect. Automatic HTTPS redirects and challenge handling stay enabled;
the canonical HTTP route also avoids deriving redirect hosts from client input. HTTP/1.1 and HTTP/2 remain enabled; HTTP/3 is available but not a CI test.

For the later public deployment, configuration can become
`EDGE_BIND_ADDRESS=0.0.0.0`, `EDGE_HTTP_PORT=80`, `EDGE_HTTPS_PORT=443`,
`CADDY_SITE_ADDRESS=<real-domain>` (no scheme or port). Container listeners remain
8080/8443; redirects explicitly include the standard `:443` port. This is a future configuration contract,
not an instruction to deploy or obtain certificates in this phase.

`PUBLIC_BASE_URL` is independent of Docker binding. When connection is enabled it
must be the externally reachable HTTPS origin: `https://localhost:8443` locally
or `https://<real-domain>` later. Existing validation is unchanged. Generated
connect links and cookie security use this configured origin, never `Host`,
`Forwarded`, or `X-Forwarded-Host` request input.

## Network and header boundary

Use Docker Compose **2.33.1 or newer** for `gw_priority` (including Compose v5).
Caddy joins only `edge`; app joins `edge` and `backend`; PostgreSQL joins only
`backend`. The backend network is internal. The app's edge gateway has priority
1, preserving future outbound connectivity. A runtime route-table assertion
verifies this without contacting a provider. Docker network attachment requires
trusted host administration; the app is the only production upstream on edge.

Caddy is the first edge proxy. An explicitly ordered `route` deletes incoming
`Forwarded` and all `X-Forwarded-*` headers **before** `reverse_proxy app:8080`
creates its own client address, HTTPS scheme and original host metadata.
There are no trusted proxy ranges or manually preserved client chains. The
[request header directive](https://caddyserver.com/docs/caddyfile/directives/request_header)
and [reverse proxy defaults](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#defaults)
define this boundary. Any future CDN requires a separately reviewed trust policy.

Only the production Compose app receives
`SERVER_FORWARD_HEADERS_STRATEGY=FRAMEWORK`. Boot's supported setting makes Spring
recognize HTTPS despite internal HTTP. No global development setting or custom
filter is added. Spring still owns route authorization, CSRF, CSP, HSTS,
Referrer-Policy, frame/content-type protection, no-store and secure connect
cookies. Caddy does not duplicate these policies.

## Local startup and explicit trust

For a persistent local stack, follow [container deployment](container-deployment.md)
to create an ignored environment file with locally generated database credentials
and a stable master key. Keep all three provider switches false in both the file
and invoking shell. Startup and GET health requests need no VULCAN or Telegram
traffic. For fully disposable, generated synthetic configuration use:

```shell
python scripts/container/verify.py
```

For a configured local stack with the default ports:

```shell
docker compose --env-file .env.production -f compose.production.yml up -d --wait
docker compose --env-file .env.production -f compose.production.yml cp caddy:/data/caddy/pki/authorities/local/root.crt /tmp/vsm-local-root.crt
curl --cacert /tmp/vsm-local-root.crt --fail https://localhost:8443/actuator/health/readiness
curl --head http://localhost:8080/actuator/health/readiness
rm /tmp/vsm-local-root.crt
```

On PowerShell use a temporary path under `$env:TEMP`, `curl.exe` and
`Remove-Item -LiteralPath` for that public certificate. Trust only this public
root in the test client. Never export the CA private key, copy the whole data
directory, use insecure TLS flags, or commit generated certificates. A browser
needs explicit local CA trust; the harness verifies hostname and chain using
Python's standard verified SSL context without modifying the OS trust store.

## State, security and lifecycle

Named `caddy_data:/data` and `caddy_config:/config` volumes survive recreation.
Treat `/data` as sensitive: it contains local CA private keys and future ACME
certificate/account state. Only the tracked Caddyfile is bind-mounted, read-only.
There is no Docker socket, application/database secret environment, or log mount.

The official image retains UID 0. Its root filesystem is read-only, all Linux
capabilities are dropped except `NET_BIND_SERVICE`, `no-new-privileges` is enabled, and listeners use high
ports. Only `/data` and `/config` are writable mounted surfaces; no tmpfs is needed.
`skip_install_trust` avoids attempting to modify the container trust store.
The image runs Caddy directly without a shell PID-1 wrapper. The official binary
has `cap_net_bind_service=ep`; Docker returns `exec: operation not permitted` if
that capability is absent from its bounding set, even for high listeners and
non-root execution. This tested single-capability exception avoids a custom
image or startup binary mutation; no other capability is retained.

The admin API is disabled with `admin off`; port 2019 is neither listening nor
published. Configuration changes require controlled restart/recreation, not
`caddy reload`. Caddy waits for app health at initial startup, remains running
during later DB/app outages, and uses `restart: unless-stopped`. No generic
container healthcheck is added: app health and verified edge smoke cover serving
without insecure TLS or public-DNS hairpin assumptions.

```shell
docker compose --env-file .env.production -f compose.production.yml restart caddy
docker compose --env-file .env.production -f compose.production.yml up -d --no-deps --force-recreate --no-build caddy
docker compose --env-file .env.production -f compose.production.yml stop caddy
docker compose --env-file .env.production -f compose.production.yml start caddy
docker compose --env-file .env.production -f compose.production.yml down
```

Caddy's graceful drain budget is 20 seconds inside Docker's 30-second stop budget.
Process logs stay on stdout/stderr with `json-file` rotation of 10 MiB x 3.
**Request access logging is disabled:** `/connect/<token>` is a secret capability.
Do not enable a site `log` directive or debug request logging. The smoke checks
that a unique synthetic token path never appears in Caddy logs during successful
proxying. Process/error logs can contain request context on failures; restrict
operator access and do not publish raw diagnostics containing sensitive requests.

`down` preserves database and Caddy volumes. **`down -v` deletes all three named
volumes**, losing database data and TLS/account/CA identity; it is only used by
the harness for its own unique disposable project. Unnecessary deletion of future
public Caddy state can cause certificate reissuance. App-only recovery preserves
Caddy and PostgreSQL; see [database recovery](database-backup-restore.md).

## Verification and next phase

The existing `Container build and smoke` CI job builds the app once. It tests the
actual Caddy version/adapted config, trusted local TLS and hostname, redirect,
three status-only HTTPS health endpoints, unknown-route rejection, secure Spring
HSTS, direct-port absence, network membership/default route and secret separation.
A tiny Java helper in the optional smoke target proves malicious forwarded
headers are removed through the same Caddyfile. It is absent from production.

The only live connection fixture uses synthetic local configuration and GETs
without a valid token; it never posts credentials or invokes authentication.
Monitoring and Telegram stay disabled. All provider flags return to false before
the unchanged backup/restore regression suite. Counts of zero provider requests
follow from this configuration and empty synthetic account state, not packet
capture. Chromium runs offline under `--network none`. The harness also verifies
DB outage/recovery through HTTPS without app/Caddy restart, app-only recreation,
Caddy clean SIGTERM and recreation with the original trusted CA fingerprint.

PR #18 still needs a real VPS/domain, DNS A/AAAA decisions, public 80 and 443 TCP
(optional 443 UDP), firewall, real public origin/site settings, automatic public
certificate issuance, persistent production storage, start-on-boot/deployment,
update/rollback, and scheduled/off-host backup policy. None is implemented here.
