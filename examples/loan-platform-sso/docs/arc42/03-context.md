# 03 System Scope and Context — loan-platform-sso

## Business Context

```
 ┌────────────────────────────────────────────────────────────────────────┐
 │                         loan-platform-sso                              │
 │                                                                        │
 │  ┌──────────────────────┐       ┌──────────────────────────────────┐  │
 │  │  Browser (alice/eve/ │       │  LoanWorker (service account:    │  │
 │  │  admin)              │       │  worker/worker)                  │  │
 │  └──────────┬───────────┘       └────────────────┬─────────────────┘  │
 │             │ HTTPS (8080)                        │ HTTP Basic          │
 │             ▼                                     ▼                    │
 │  ┌────────────────────┐          ┌──────────────────────────────────┐  │
 │  │  nginx + oauth2-   │          │  Operaton Engine (REST-only)     │  │
 │  │  proxy (TLS, SSO)  │◄────────►│  /engine-rest                    │  │
 │  └────────────────────┘          └──────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────────────────┘
               │                              │
               ▼                              ▼
 ┌─────────────────────────┐    ┌──────────────────────────┐
 │  Keycloak (IdP)         │    │  PostgreSQL databases     │
 │  realm: operaton        │    │  (3 instances)            │
 └─────────────────────────┘    └──────────────────────────┘
               │
               ▼
 ┌─────────────────────────┐
 │  Flowset Control        │
 │  Flowset Tasklist       │
 └─────────────────────────┘
```

### Actors

| Actor | System interaction |
|---|---|
| **alice** | Browser user (group `employees`). Starts loan applications in Tasklist, views the welcome page. |
| **eve** | Browser user (group `underwriter`). Claims and completes Underwriter Review tasks in Tasklist. |
| **admin** | Browser user (group `operaton-admin`). Monitors all process instances in Flowset Control, has full engine access. |
| **worker** (service account) | Non-browser automated client. Fetches and completes external tasks on topics `credit-scoring` and `notification` using HTTP Basic credentials validated by Keycloak. |

### External Systems

| System | Role in this example |
|---|---|
| **Keycloak** (`quay.io/keycloak/keycloak:26.6.3`) | OIDC identity provider. Authenticates all human users and validates worker service-account credentials via ROPC. Hosts the `operaton` realm with all clients, groups, and users. |
| **Flowset Control** (`flowset/flowset-control-community:latest`) | Task management and process monitoring UI. Connects to Keycloak via OIDC and to the engine via the REST API. Published as `linux/amd64` only. |
| **Flowset Tasklist** (`flowset/flowset-tasklist-react-community:latest`) | React SPA for end users to start processes and complete user tasks. Calls `/engine-rest` through the nginx proxy. Published as `linux/amd64` only. |

---

## Technical Context

### Network Boundary

All inter-container traffic flows over Docker's internal bridge network
(`loan-platform-sso_default`). The network boundary at the edge is nginx,
which:

1. Terminates TLS using the self-signed certificate from `nginx/certs/`.
2. Performs an `auth_request` to oauth2-proxy for every protected route.
3. Proxies authenticated requests to the appropriate upstream container.

No container other than nginx exposes a port on the Docker host.

### Communication Channels

| Channel | Protocol | Auth |
|---|---|---|
| Browser → nginx | HTTPS (port 8080 → 8443) | TLS; session cookie validated by oauth2-proxy |
| nginx → oauth2-proxy | HTTP (internal, port 4180) | `auth_request` sub-request; cookie forwarded |
| nginx → Flowset Control | HTTP (internal, port 8081) | Bearer token injected by oauth2-proxy via `X-Auth-Request-Access-Token` |
| nginx → Flowset Tasklist | HTTP (internal, port 3000) | Bearer token (same mechanism) |
| nginx → Operaton engine | HTTP (internal, port 8080) | Bearer token forwarded to `/engine-rest` |
| Browser → Keycloak (login) | HTTP (internal, port 8080 via nginx proxy) | Username + password (ROPC or Authorization Code with PKCE) |
| LoanWorker → engine | HTTP (internal, port 8080) | HTTP Basic (`worker:worker`) |
| oauth2-proxy → Keycloak | HTTP (internal, port 8080) | PKCE Authorization Code flow; client secret |
| Flowset Control → Keycloak | HTTP (internal, port 8080) | OIDC (client: `flowset-control`) |
| Engine → Keycloak | HTTP (internal, port 8080) | ROPC (validates Basic credentials via `KeycloakIdentityProviderPlugin`) |
| Containers → PostgreSQL | TCP (internal, port 5432) | Username + password (per-service throwaway credentials) |
