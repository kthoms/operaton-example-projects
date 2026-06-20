# 05 Building Block View — operaton-flowset-sso

## Level 1: Top-Level Containers

The system is composed of 11 Docker containers defined in `docker-compose.yml`.

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  operaton-flowset-sso (Docker Compose stack)                                    │
│                                                                              │
│  ┌──────────────┐   ┌───────────────┐   ┌──────────────────────────────┐   │
│  │  nginx       │──►│  oauth2-proxy │──►│  Keycloak                    │   │
│  │  (TLS entry) │   │  (OIDC SSO)   │   │  (Identity Provider)         │   │
│  └──────┬───────┘   └───────────────┘   └──────────────┬───────────────┘   │
│         │                                               │                   │
│   ┌─────▼──────┐ ┌──────────────┐ ┌───────────────┐   │                   │
│   │  welcome   │ │  Flowset     │ │  Flowset       │   │                   │
│   │  page      │ │  Control     │ │  Tasklist      │   │                   │
│   │  (static)  │ │  (UI)        │ │  (SPA)         │   │                   │
│   └────────────┘ └──────────────┘ └───────┬────────┘   │                   │
│                                           │             │                   │
│                         ┌─────────────────▼─────────────▼─────────────┐   │
│                         │  Operaton Engine (REST-only, Keycloak plugin) │   │
│                         └──────────────────────┬───────────────────────┘   │
│                                                 │                           │
│                         ┌───────────────────────▼──┐                       │
│                         │  LoanWorker               │                       │
│                         │  (external-task, Java)    │                       │
│                         └───────────────────────────┘                       │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  PostgreSQL ×3: postgres-operaton | postgres-keycloak | postgres-flowset  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  keycloak-init (one-shot sidecar — exits 0 after seeding)                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## Container Responsibilities

### `nginx` — TLS Entry Point and Reverse Proxy

- **Image:** `nginx:1.27-alpine`
- **Source:** `nginx/nginx.conf`, `nginx/html/`, `nginx/certs/`
- **Responsibilities:**
  - Terminates TLS using the self-signed certificate from `nginx/certs/`.
  - Validates every request via `auth_request /oauth2/auth` (delegated to oauth2-proxy).
  - Serves the static welcome page from `nginx/html/index.html`.
  - Proxies `/control/` → `flowset-control:8081`.
  - Proxies `/tasklist/` → `flowset-tasklist:3000`.
  - Proxies `/engine-rest/` → `operaton:8080/engine-rest/`.
  - Extracts the access token from the `X-Auth-Request-Access-Token` header and forwards it as `Authorization: Bearer` to upstreams.

### `oauth2-proxy` — OIDC Session Gateway

- **Image:** `quay.io/oauth2-proxy/oauth2-proxy:v7.15.3`
- **Source:** `oauth2-proxy/oauth2-proxy.cfg`
- **Responsibilities:**
  - Maintains user sessions as encrypted cookies (`_oauth2_proxy`).
  - Performs the PKCE Authorization Code flow against Keycloak.
  - Returns `202 Accepted` when the session cookie is valid (used by nginx `auth_request`).
  - Returns `401 Unauthorized` when no valid session exists (nginx then serves the sign-in page).
  - Sets `X-Auth-Request-Access-Token` and `X-Auth-Request-User` headers for nginx to forward.

### `keycloak` — Identity Provider

- **Image:** `quay.io/keycloak/keycloak:26.6.3`
- **Source:** `keycloak/themes/operaton/` (custom login theme)
- **Responsibilities:**
  - Hosts the `operaton` realm (seeded by `keycloak-init`).
  - Authenticates users via the Authorization Code + PKCE flow (browser clients).
  - Validates service-account credentials via the Resource Owner Password Credentials (ROPC) endpoint (engine Keycloak plugin, worker).
  - Issues access tokens with realm role claims (`operaton-admin`, `underwriter`, etc.).

### `keycloak-init` — Realm Seeding Sidecar

- **Image:** `quay.io/keycloak/keycloak:26.6.3` (reused; entrypoint overridden)
- **Source:** `keycloak/seed-realm.sh`
- **Responsibilities:**
  - Creates the `operaton` realm.
  - Creates OIDC clients: `oauth2-proxy`, `flowset-control`, `operaton-identity-service`.
  - Creates groups: `operaton-admin`, `employees`, `underwriter`.
  - Creates users: `alice`, `eve`, `admin`, `worker` (with group memberships).
  - Runs exactly once (`restart: "no"`); downstream services gate on `service_completed_successfully`.

### `operaton` — BPMN Process Engine

- **Image:** `operaton-flowset-sso-engine:local` (built from `engine/Dockerfile`)
- **Source:** `engine/Dockerfile`, `engine/config/default.yml`, `engine/resources/`
- **Responsibilities:**
  - Runs the Operaton Run distribution in REST-only mode (`--rest`).
  - Loads `operaton-keycloak-all-2.1.0.jar` from `userlib/` for identity-provider integration.
  - Auto-deploys `loan-application.bpmn` and `risk-assessment.dmn` from `resources/`.
  - Exposes `/engine-rest` (HTTP Basic auth, validated by `KeycloakIdentityProviderPlugin`).
  - Stores all engine data in `postgres-operaton`.

### `worker` — External-Task Worker

- **Image:** `operaton-flowset-sso-worker:local` (built from `worker/Dockerfile`)
- **Source:** `src/main/java/.../LoanWorker.java`, `src/main/java/.../KeycloakTokenProvider.java`
- **Responsibilities:**
  - Polls `/engine-rest` for external tasks on topics `credit-scoring` and `notification`.
  - `credit-scoring` handler: derives `creditScore` from `loanAmount` (or uses a pre-set value).
  - `notification` handler: maps `riskLevel` to `loanDecision` (`APPROVED` or `REJECTED`).
  - Authenticates with HTTP Basic (`worker:worker`) in `BASIC_IDP` mode.

### `flowset-control` — Process Monitoring UI

- **Image:** `flowset/flowset-control-community:latest` (linux/amd64)
- **Responsibilities:**
  - Provides a task-management and process-monitoring web UI.
  - Authenticates users via OIDC (client `flowset-control` in Keycloak).
  - Stores its own operational data in `postgres-flowset`.

### `flowset-tasklist` — Task Management SPA

- **Image:** `flowset/flowset-tasklist-react-community:latest` (linux/amd64)
- **Responsibilities:**
  - Serves a React SPA for end users to start processes and complete user tasks.
  - Calls `/engine-rest` through the nginx proxy (access token forwarded as Bearer).
  - Stateless; no database of its own.

### `postgres-operaton`, `postgres-keycloak`, `postgres-flowset` — Databases

- **Image:** `postgres:16-alpine` (×3)
- **Responsibilities:** Persistent storage for the engine, Keycloak, and Flowset Control respectively. Each runs as an isolated instance with dedicated credentials.
