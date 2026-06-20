# 10 Quality Requirements and Risks — operaton-flowset-sso

## Known Risks

### RISK-001: Live Keycloak ROPC Probe Not Performed (ADR-001)

**Description:** ADR-001 concludes that `BASIC_IDP` (HTTP Basic validated via
`KeycloakIdentityProviderPlugin` / ROPC) is the correct auth mode for the Run
distribution. This conclusion is based on static analysis of the Run distribution,
the Keycloak plugin documentation, and the Flowset Tasklist architecture. A live
end-to-end probe of the ROPC validation path against a running Keycloak was not
performed as part of the architecture spike.

**Impact:** If the plugin's ROPC validation is not triggered as expected (e.g.,
because the plugin requires a configuration step not covered by `default.yml`),
the engine would reject all credentials and the worker and Flowset Tasklist would
be unable to authenticate.

**Mitigation:** The integration test (`LoanPlatformIT`) exercises the full path:
engine + Keycloak + worker. A green IT run confirms the ROPC validation works
end-to-end. The `./mvnw verify` output is the acceptance evidence.

---

### RISK-002: oauth2-proxy Access-Token Injection Complexity

**Description:** The nginx configuration forwards the Keycloak access token from
the `X-Auth-Request-Access-Token` header (set by oauth2-proxy) to upstreams as
`Authorization: Bearer`. This requires `set_xauthrequest = true` and
`pass_access_token = true` in `oauth2-proxy.cfg` and correct nginx `proxy_set_header`
directives. Misconfiguration results in 401 errors from Flowset Control or Flowset
Tasklist without an obvious error message.

**Impact:** Flowset UIs silently fail to authenticate if the Bearer token is not
correctly forwarded. Debugging requires inspecting nginx access logs and oauth2-proxy
debug logs.

**Mitigation:** The nginx configuration is tested against the running stack during
Task 12 bring-up. The exact header names are verified in `nginx/nginx.conf`.

---

### RISK-003: Flowset Images — linux/amd64 Only

**Description:** `flowset/flowset-control-community:latest` and
`flowset/flowset-tasklist-react-community:latest` are published as `linux/amd64`
only. On Apple Silicon (arm64) hosts, Docker Desktop runs these via Rosetta 2
or QEMU emulation.

**Impact:** Emulation introduces startup latency (typically 2–3× slower) and
occasional runtime instability. On Linux x86_64 CI agents the images run natively.

**Mitigation:** `docker-compose.yml` specifies `platform: linux/amd64` explicitly
for these two services to avoid ambiguous platform resolution errors. Users on
Apple Silicon should ensure Docker Desktop's "Use Rosetta for x86/amd64 emulation
on Apple Silicon" option is enabled.

---

### RISK-004: No Production TLS or Secrets Management

**Description:** The stack runs on plain HTTP (TLS is opt-in). All credentials
(Keycloak admin, DB passwords, client secrets, cookie secret) are hardcoded dev
values. `nginx/gen-cert.sh` is provided for those who want a self-signed cert but
is not mounted by default.

**Impact:** The project cannot be deployed to a production or staging environment
without additional configuration (TLS reverse proxy, secrets vault, network hardening).

**Mitigation:** This is an example project. The README and `02-constraints.md`
explicitly state that credentials are throwaway dev values. Production hardening
is out of scope.

---

### RISK-005: Engine OpenAPI Spec URL for Redoc

**Description:** The Redoc-rendered REST API docs at `/engine-rest` rely on the
engine exposing its OpenAPI specification at a known URL. If the `operaton/operaton`
Run distribution does not expose the spec at the expected path, the Redoc page
will load but show an error or an empty spec.

**Impact:** The Redoc page at `http://localhost:8080/engine-rest` would appear
broken (no API documentation rendered), but all other functionality is unaffected.

**Mitigation:** The correct OpenAPI spec URL should be verified against the running
stack. The `nginx/html/engine-rest-docs.html` file contains the `spec-url` value;
update it if the engine serves the spec at a different path.

---

## Quality Attribute Scenarios

### QAS-001: SSO Session Persistence

**Stimulus:** A user authenticates once and navigates between the welcome page,
Flowset Control, and Flowset Tasklist within a single browser session.

**Expected response:** The user is not prompted for credentials again. The session
cookie (`_oauth2_proxy`) is accepted by oauth2-proxy on every `auth_request`
sub-request. All three services receive the forwarded Bearer token.

---

### QAS-002: Worker Reconnection After Engine Restart

**Stimulus:** The `operaton` container is restarted while the worker is running.

**Expected response:** The worker's external-task client detects the connection
failure and retries the `fetchAndLock` poll. After the engine becomes healthy
again, the worker resumes processing without manual intervention.

**Note:** The Operaton external-task client retries by default. No explicit
reconnection logic is needed in `LoanWorker`.

---

### QAS-003: Realm Seeding Idempotency

**Stimulus:** `keycloak-init` runs twice against the same Keycloak instance (e.g.,
after a `docker compose restart keycloak-init`).

**Expected response:** The `seed-realm.sh` script uses `kcadm.sh` commands that
check for existing objects before creating them (`get` before `create`). Duplicate
objects are not created. The exit code is 0.
