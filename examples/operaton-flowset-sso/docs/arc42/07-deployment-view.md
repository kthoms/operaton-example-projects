# 07 Deployment View — operaton-flowset-sso

This section describes the container topology as deployed by
`docker compose up` (verified during Task 12 stack bring-up).

---

## Container Topology

The stack is defined in `docker-compose.yml` and creates an isolated Docker
bridge network (`operaton-flowset-sso_default`). All inter-container communication
uses Docker's internal DNS resolver (`127.0.0.11`). Two host ports are exposed:
**8080** maps to nginx (application entry point) and **8180** maps directly to
Keycloak (required so browsers can follow OIDC authorization redirects). TLS is
opt-in — plain HTTP is the default.

A single `postgres` container hosts three schemas (`operaton`, `keycloak`,
`flowset`) each with its own dedicated user. The init script in
`postgres/init.sql` creates the schemas and users at first start.

```
Docker Host (macOS / Linux)
│
│   Port 8080 (TCP)
│       │
│       ▼
│  ┌─────────────────────────────────────────────────────────────────┐
│  │  Docker Bridge Network: operaton-flowset-sso_default               │
│  │                                                                 │
│  │  ┌──────────────────────────────────────────────────────────┐  │
│  │  │  ENTRY POINT                                              │  │
│  │  │  nginx:1.27-alpine                 (nginx)               │  │
│  │  │  - Listens: 8080/tcp (HTTP)                              │  │
│  │  │  - Host port mapping: 8080 → 8080                        │  │
│  │  │  - Serves: /usr/share/nginx/html (welcome page)          │  │
│  │  │  - auth_request → oauth2-proxy:4180                      │  │
│  │  │  - Proxies: /control/ → flowset-control:8081             │  │
│  │  │             /tasklist/ → flowset-tasklist:3000            │  │
│  │  │             /engine-rest/ → operaton:8080/engine-rest/   │  │
│  │  └──────────────────────────────────────────────────────────┘  │
│  │              │                                                  │
│  │  ┌───────────▼──────────────────────────────────────────────┐  │
│  │  │  SSO GATEWAY                                              │  │
│  │  │  oauth2-proxy:v7.15.3              (oauth2-proxy)        │  │
│  │  │  - Listens: 4180/tcp (HTTP, internal only)               │  │
│  │  │  - Provider: keycloak-oidc                               │  │
│  │  │  - Realm: operaton (PKCE, cookie-based session)          │  │
│  │  │  - Upstream: static://200 (auth-only, not a proxy)       │  │
│  │  │  - Depends on: keycloak (healthy) + keycloak-init (done) │  │
│  │  └──────────────────────────────────────────────────────────┘  │
│  │                                                                 │
│  │  ┌───────────────────────────────────────────────────────────┐ │
│  │  │  IDENTITY PROVIDER                                        │ │
│  │  │  quay.io/keycloak/keycloak:26.6.3  (keycloak)           │ │
│  │  │  - Listens: 8080/tcp (HTTP)                              │ │
│  │  │  - Host port mapping: 8180 → 8080 (browser OIDC login)  │ │
│  │  │  - Mode: start-dev                                        │ │
│  │
│  │  │  - DB: postgres:5432/platform (schema: keycloak)          │ │
│  │  │  - Theme: /opt/keycloak/themes/operaton (custom)         │ │
│  │  │  - Realm: operaton (seeded by keycloak-init)             │ │
│  │  │  - Clients: oauth2-proxy, flowset-control,               │ │
│  │  │             operaton-identity-service                     │ │
│  │  └───────────────────────────────────────────────────────────┘ │
│  │                                                                 │
│  │  ┌───────────────────────────────────────────────────────────┐ │
│  │  │  INIT JOB (exits 0 after first run)                       │ │
│  │  │  quay.io/keycloak/keycloak:26.6.3  (keycloak-init)      │ │
│  │  │  - Entrypoint: bash /seed/seed-realm.sh                  │ │
│  │  │  - Creates: realm operaton, clients, groups, users        │ │
│  │  │  - restart: "no"                                          │ │
│  │  │  - Depends on: keycloak (healthy)                        │ │
│  │  └───────────────────────────────────────────────────────────┘ │
│  │                                                                 │
│  │  ┌───────────────────────────────────────────────────────────┐ │
│  │  │  PROCESS ENGINE                                           │ │
│  │  │  operaton-flowset-sso-engine:local   (operaton)            │ │
│  │  │  Base: operaton/operaton:2.1.1 (Run distribution)        │ │
│  │  │  - Listens: 8080/tcp (HTTP, internal only)               │ │
│  │  │  - Mode: --rest (no Cockpit/Tasklist WARs)               │ │
│  │  │  - Plugin: operaton-keycloak-all-2.1.0.jar               │ │
│  │  │  - Auth: BASIC_IDP via KeycloakIdentityProviderPlugin    │ │
│  │  │  - DB: postgres:5432/platform (schema: operaton)          │ │
│  │  │  - Deploys: loan-application.bpmn, risk-assessment.dmn  │ │
│  │  │  - Depends on: postgres-operaton (healthy) +             │ │
│  │  │                keycloak-init (completed successfully)     │ │
│  │  └───────────────────────────────────────────────────────────┘ │
│  │                                                                 │
│  │  ┌───────────────────────────────────────────────────────────┐ │
│  │  │  EXTERNAL TASK WORKER                                     │ │
│  │  │  operaton-flowset-sso-worker:local   (worker)              │ │
│  │  │  Base: eclipse-temurin:21-jre                            │ │
│  │  │  - Topics: credit-scoring, notification                  │ │
│  │  │  - Auth: BASIC_IDP (user: worker, group: operaton-admin) │ │
│  │  │  - Polls: operaton:8080/engine-rest (fetchAndLock)       │ │
│  │  │  - Depends on: operaton (healthy)                        │ │
│  │  └───────────────────────────────────────────────────────────┘ │
│  │                                                                 │
│  │  ┌───────────────────────────────────────────────────────────┐ │
│  │  │  TASK MANAGEMENT UI                                       │ │
│  │  │  flowset/flowset-control-community:latest (flowset-ctrl) │ │
│  │  │  platform: linux/amd64                                    │ │
│  │  │  - Listens: 8081/tcp (HTTP, internal)                    │ │
│  │  │  - Auth: OIDC (client: flowset-control)                  │ │
│  │  │  - Exposed at: http://localhost:8080/control/            │ │
│  │  │  - DB: postgres:5432/platform (schema: flowset)           │ │
│  │  └───────────────────────────────────────────────────────────┘ │
│  │                                                                 │
│  │  ┌───────────────────────────────────────────────────────────┐ │
│  │  │  TASKLIST FRONTEND                                        │ │
│  │  │  flowset/flowset-tasklist-react-community:latest          │ │
│  │  │  platform: linux/amd64                                    │ │
│  │  │  - Listens: 3000/tcp (HTTP, internal)                    │ │
│  │  │  - Static SPA; calls /engine-rest via nginx proxy        │ │
│  │  │  - Exposed at: http://localhost:8080/tasklist/           │ │
│  │  └───────────────────────────────────────────────────────────┘ │
│  │                                                                 │
│  │  ┌──────────────────────────────────────────────────────────┐  │
│  │  │  DATABASE                                                 │  │
│  │  │  postgres:16-alpine  (postgres)                           │  │
│  │  │    DB: platform                                           │  │
│  │  │    schema: operaton / user: operaton                      │  │
│  │  │    schema: keycloak  / user: keycloak                     │  │
│  │  │    schema: flowset   / user: flowset                      │  │
│  │  └──────────────────────────────────────────────────────────┘  │
│  └─────────────────────────────────────────────────────────────────┘
```

---

## Service Summary Table

| Container            | Image                                        | Port (internal) | Host port | Role                          |
|----------------------|----------------------------------------------|-----------------|-----------|-------------------------------|
| `nginx`              | `nginx:1.27-alpine`                          | 8080/tcp (HTTP) | **8080**  | Entry point                   |
| `oauth2-proxy`       | `quay.io/oauth2-proxy/oauth2-proxy:v7.15.3`  | 4180/tcp        | —         | OIDC SSO gateway              |
| `keycloak`           | `quay.io/keycloak/keycloak:26.6.3`           | 8080/tcp        | **8180**  | Identity provider             |
| `keycloak-init`      | `quay.io/keycloak/keycloak:26.6.3`           | —               | —         | One-shot realm seeder         |
| `operaton`           | `operaton-flowset-sso-engine:local`            | 8080/tcp        | —         | BPMN process engine (REST)    |
| `worker`             | `operaton-flowset-sso-worker:local`            | —               | —         | External task worker          |
| `flowset-control`    | `flowset/flowset-control-community:latest`   | 8081/tcp        | —         | Task management UI (amd64)    |
| `flowset-tasklist`   | `flowset/flowset-tasklist-react-community:latest` | 3000/tcp   | —         | Tasklist SPA (amd64)          |
| `postgres`           | `postgres:16-alpine`                         | 5432/tcp        | —         | Shared database (3 schemas)   |

Total: **9 containers** (1 ephemeral/init: keycloak-init exits 0; worker and all others stay running).

---

## Startup Order (dependency chain)

```
postgres (healthy)
      ├─► keycloak (healthy)
      │       ├─► keycloak-init (completed-successfully)
      │       │       └─► oauth2-proxy
      │       └─► oauth2-proxy (also waits for keycloak-init)
      ├─► operaton (healthy)
      │       ├─► worker
      │       ├─► flowset-control
      │       └─► flowset-tasklist
      └─► flowset-control

nginx (waits for oauth2-proxy, operaton, flowset-control, flowset-tasklist)
```

---

## Notes on Platform Compatibility

The `flowset-control` and `flowset-tasklist` images are published as
`linux/amd64` only. On Apple Silicon (arm64) hosts, the `docker-compose.yml`
specifies `platform: linux/amd64` for these two services so Docker Desktop
runs them via Rosetta 2 / QEMU translation. All other images are multi-arch
and run natively.

---

## Host Port Mapping

| Host address         | Protocol | Maps to                    | Purpose                                      |
|----------------------|----------|----------------------------|----------------------------------------------|
| `localhost:8080`     | HTTP     | `nginx:8080`               | Application entry point                      |
| `localhost:8180`     | HTTP     | `keycloak:8080`            | Keycloak login UI (browser OIDC redirects)   |

Keycloak runs without a fixed `KC_HOSTNAME` (dynamic hostname mode). The
browser always reaches Keycloak at `localhost:8180`, so Keycloak stamps all
JWTs with `iss=http://localhost:8180/realms/operaton`. oauth2-proxy is
configured with `skip_oidc_discovery=true` and `oidc_issuer_url=localhost:8180`
(used only as a string to compare against the JWT `iss` claim — no connection
is made). Flowset Control uses explicit individual endpoint URIs instead of
`issuer-uri`; this bypasses Spring Boot's issuer validation and lets the
app accept tokens whose `iss=localhost:8180` without needing to reach that
host from inside the container. TLS is opt-in; add a reverse proxy in front
of port 8080 for production use.
