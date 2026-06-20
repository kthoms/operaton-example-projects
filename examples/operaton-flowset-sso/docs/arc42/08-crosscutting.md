# 08 Cross-Cutting Concepts — operaton-flowset-sso

## Security

### Browser-facing SSO

All browser-facing routes are protected by the nginx `auth_request` directive.
Every HTTP request to a protected route triggers a sub-request to
`oauth2-proxy:4180/oauth2/auth`. If the request carries a valid session cookie,
oauth2-proxy returns `202 Accepted` and nginx proceeds to serve or proxy the
request. If not, oauth2-proxy returns `401 Unauthorized` and nginx responds with
the oauth2-proxy sign-in page (configured via `error_page 401 = /oauth2/sign_in`).

The OIDC Authorization Code flow uses PKCE (`code_challenge_method = S256`),
which eliminates the need for a client secret in the browser leg of the flow.
After successful authentication, oauth2-proxy issues an encrypted session cookie
(`_oauth2_proxy`) bound to the domain.

### Engine REST Authentication (BASIC_IDP)

The engine's `/engine-rest` endpoint uses HTTP Basic authentication. Credentials
are not validated locally — they are forwarded to Keycloak's Resource Owner
Password Credentials (ROPC) endpoint by `KeycloakIdentityProviderPlugin`. This
means every Basic auth request to the engine results in a Keycloak token request.

The `BEARER_OAUTH2` mode (engine `--oauth2` flag) is intentionally not activated.
See ADR-001 in `docs/arc42/09-architecture-decisions.md` for the full rationale.

### Access Token Forwarding

After the oauth2-proxy `auth_request` check, nginx extracts the Keycloak access
token from the `X-Auth-Request-Access-Token` response header (set by
`pass_access_token = true` in oauth2-proxy) and forwards it as
`Authorization: Bearer <token>` to upstreams such as Flowset Control and
Flowset Tasklist. This avoids redundant OIDC token requests from application
containers.

### Credentials at Rest

All credentials in this example are dev/throwaway values:

| Secret | Value | Where set |
|---|---|---|
| Keycloak admin | `admin/admin` | `docker-compose.yml` env vars |
| User passwords | `alice/alice`, `eve/eve`, `worker/worker` | `keycloak/seed-realm.sh` |
| DB passwords | same as username | `docker-compose.yml` env vars |
| oauth2-proxy cookie secret | fixed demo value | `oauth2-proxy/oauth2-proxy.cfg` |
| Client secrets | fixed demo values | `keycloak/seed-realm.sh` |

No secrets are stored in environment variables that cross a trust boundary. All
containers communicate over the Docker bridge network.

---

## Logging

All containers write logs to Docker's default JSON file driver. No centralised
log aggregation is configured in this example (out of scope).

The Operaton engine and the external-task worker use SLF4J with `slf4j-simple`
(test scope). Integration-test output includes Testcontainers Docker-pull progress
and container lifecycle events at INFO level.

Worker and engine log messages are prefixed with the Operaton client logger names
(`org.operaton.bpm.client.*`).

---

## Configuration Patterns

### Environment Variables

Runtime behaviour is controlled exclusively through environment variables. No
configuration files are mounted into the worker container. The worker reads:

| Variable | Default | Purpose |
|---|---|---|
| `ENGINE_REST_URL` | `http://operaton:8080/engine-rest` | Engine base URL |
| `ENGINE_AUTH_MODE` | `BASIC_IDP` | Authentication mode (`BASIC_IDP` or `BEARER_OAUTH2`) |
| `ENGINE_USER` | `worker` | HTTP Basic username (BASIC_IDP mode) |
| `ENGINE_PASSWORD` | `worker` | HTTP Basic password (BASIC_IDP mode) |
| `KEYCLOAK_TOKEN_URL` | Keycloak ROPC endpoint | Token URL (BEARER_OAUTH2 mode only) |
| `WORKER_CLIENT_ID` | `worker` | OIDC client ID (BEARER_OAUTH2 mode only) |
| `WORKER_CLIENT_SECRET` | `worker-secret` | OIDC client secret (BEARER_OAUTH2 mode only) |

Engine configuration is in `engine/config/default.yml` (mounted into the engine
container at build time). Keycloak, Flowset, and oauth2-proxy are configured
via Docker Compose environment variables.

### Health Checks and Startup Order

Every service that is a dependency declares a `healthcheck` in `docker-compose.yml`.
Downstream services use `depends_on: condition: service_healthy` (or
`service_completed_successfully` for init containers). This ensures containers
start in a deterministic order and that the engine does not attempt to connect to
Keycloak before the realm is seeded.

See `docs/arc42/07-deployment-view.md` for the full startup dependency chain.

---

## Testability

Integration tests in `LoanPlatformIT` replicate the production topology at the
container level:

- Two PostgreSQL instances (engine and Keycloak) on a shared Testcontainers network.
- Keycloak started from the same image as in `docker-compose.yml`.
- Realm seeding via `execInContainer("bash", "/seed/seed-realm.sh")` — the same
  script used in production.
- Engine built from `engine/Dockerfile` using Testcontainers' `ImageFromDockerfile`.
- Worker started in-process using `LoanWorker.start(baseUrl, credentials)`.

Asynchronous assertions use Awaitility (no `Thread.sleep`). Tests poll the engine
history API until the expected end event appears or the timeout (30 seconds) expires.
