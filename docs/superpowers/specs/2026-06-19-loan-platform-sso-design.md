# Design: loan-platform-sso — Integrated Operaton + Keycloak + Flowset Platform

**Date:** 2026-06-19
**Status:** Approved
**Example directory:** `examples/loan-platform-sso/`

## Context

A showcase example project for `operaton-example-projects` demonstrating a
complete, secured BPM platform running entirely in Docker Compose:

- **Operaton** engine (`operaton/operaton` Spring Boot distribution) with its
  built-in webapps disabled, exposing only the REST API.
- **Keycloak** as the single identity provider (OIDC).
- **Operaton Keycloak plugin** (identity provider + JWT module) so engine
  users/groups resolve from Keycloak and `/engine-rest` accepts Keycloak
  Bearer tokens.
- **Flowset Control** and **Flowset Tasklist** as the operations and human-task
  UIs (replacing Operaton's own webapps).
- **nginx** (self-signed TLS) + **oauth2-proxy** as a single authenticating
  edge gateway giving one login across the whole stack.
- The **loan-application** use case (ported from `operaton-examples`) as the
  demo process, adapted to run **without any Spring application / Java
  delegates** — service work is done by a standalone external-task worker.

The example deliberately avoids an embedded Spring Boot application. The only
custom Java is a small, non-Spring external-task worker.

### Key research findings that shaped this design

1. `operaton/operaton` is the **Spring Boot self-contained** distribution (not
   Tomcat; Tomcat is `operaton/tomcat`). It ships **no** OIDC/Keycloak wiring.
2. The Operaton Keycloak plugin (`org.operaton.bpm.extension:operaton-keycloak`,
   fat artifact `operaton-keycloak-all`; plus `operaton-keycloak-jwt`) provides
   a read-only identity provider and a JWT auth path for the REST API.
3. **Flowset Studio is an IntelliJ IDEA plugin**, not a web app — it is out of
   scope for the stack. The deployable Flowset web apps are **Control**
   (`flowset/flowset-control-community`, OIDC-capable, needs its own Postgres)
   and **Tasklist** (`flowset/flowset-tasklist-react-community`, React app that
   calls `/engine-rest` with **Basic auth only — no OIDC**).
4. Because Tasklist and the engine webapps cannot do OIDC themselves, a single
   sign-on experience requires an **edge OIDC gateway** (oauth2-proxy) that
   logs the user in once and forwards an identity to each backend.

## Decisions (resolved during brainstorming)

| # | Decision | Choice |
|---|---|---|
| 1 | Authentication model | Edge OIDC proxy (nginx TLS → oauth2-proxy → backends) |
| 2 | Welcome-page tiles | Two tiles: Flowset Control, Flowset Tasklist (Studio dropped) |
| 3 | Process logic without delegates | External task workers |
| 4 | Worker runtime | Plain Java external-task client (non-Spring) |
| 5 | Automated test scope | Smoke + core e2e (Keycloak + engine + worker); UIs manual |
| 6 | Theming scope | Welcome page + Keycloak login theme (itemis colors); Flowset apps stock |
| 7 | Architecture docs | arc42 style under `docs/arc42/`, kept up to date |

## Architecture

### Container topology

Single `docker-compose.yml`. One public entry point: **nginx** on
`https://localhost:8080` (self-signed TLS).

| Container | Image | Role |
|---|---|---|
| `nginx` | `nginx` (stock) | TLS edge; serves static welcome + Redoc pages; reverse proxy |
| `oauth2-proxy` | `quay.io/oauth2-proxy/oauth2-proxy` | OIDC client; single login; injects `Authorization: Bearer` upstream |
| `keycloak` | `quay.io/keycloak/keycloak` | Identity provider |
| `postgres-keycloak` | `postgres:16-alpine` | Keycloak DB |
| `keycloak-init` | `quay.io/keycloak/keycloak` (entrypoint = kcadm script) | Sidecar; seeds realm/clients/groups/users, then exits |
| `operaton` | **custom image** `FROM operaton/operaton:2.1.1` | Engine + Keycloak plugin + JWT module; webapps disabled |
| `postgres-operaton` | `postgres:16-alpine` | Engine DB |
| `worker` | **custom image** (plain Java external-task client) | Handles `credit-scoring` + `notification` external tasks |
| `flowset-control` | `flowset/flowset-control-community:latest` | Operations UI |
| `postgres-flowset` | `postgres:16-alpine` | Flowset Control DB |
| `flowset-tasklist` | `flowset/flowset-tasklist-react-community:latest` | Human-task UI |

### Request routing (nginx → oauth2-proxy → backend)

All paths require an authenticated oauth2-proxy session.

| Path | Backend | Notes |
|---|---|---|
| `/` | nginx static | itemis-themed welcome page, tiles → `/control/`, `/tasklist/` |
| `/control/` | `flowset-control:8081` | Flowset Control |
| `/tasklist/` | `flowset-tasklist:3000` | Flowset Tasklist |
| `= /engine-rest` | nginx static | Redoc HTML rendering Operaton OpenAPI spec |
| `/engine-rest/` | `operaton:8080` | REST resources; Bearer token injected |

nginx uses `location = /engine-rest` (exact → Redoc page) vs
`location /engine-rest/` (prefix → engine proxy) to separate the docs page
from the live API.

## Authentication flow

1. Browser requests any `https://localhost:8080/...` path. oauth2-proxy finds
   no session → 302 redirect to the Keycloak login page (itemis-themed).
2. User authenticates (e.g. `alice`/`alice`). Keycloak redirects back;
   oauth2-proxy establishes a session cookie.
3. For each proxied request oauth2-proxy injects the Keycloak **access token**
   as `Authorization: Bearer …` to the upstream.
4. **Engine**: the custom image's Keycloak identity provider plugin resolves
   users/groups from Keycloak; the JWT module makes `/engine-rest` accept the
   Bearer token.
5. **Worker**: authenticates independently via Keycloak **client-credentials**
   (service account) → its own Bearer token → polls the engine directly on the
   internal Docker network (not through nginx).
6. **Flowset Control**: sits behind the proxy; its engine connection uses a
   service credential (Flowset's per-engine credential model — no per-user
   token pass-through).
7. **Flowset Tasklist**: configured with no engine credentials; its browser
   calls to `/engine-rest` ride the same proxy session and receive the
   injected Bearer.

### Primary technical risk

That `/engine-rest` reliably accepts the proxy-injected Keycloak Bearer token
(JWT module issuer/audience configuration), and that Tasklist tolerates
proxy-injected auth rather than insisting on its own Basic credentials. The
implementation plan's **first task is a spike** that proves a Keycloak Bearer
token authenticates against `/engine-rest` on the custom engine image, before
any other component is built. If the JWT path proves unworkable, the fallback
is Basic-auth against the engine with the identity provider validating
passwords via the resource-owner grant (documented as a deviation).

## Custom engine image

`operaton/operaton:2.1.1` ships no Keycloak wiring, so a thin custom image adds
it:

```dockerfile
FROM operaton/operaton:2.1.1
# plugin jars resolved + copied in by the module build (versions pinned there)
COPY operaton-keycloak-all-<ver>.jar   <engine userlib dir>/
COPY operaton-keycloak-jwt-<ver>.jar   <engine userlib dir>/
# identity provider + JWT auth + webapps-off configured via env / application.yaml
```

- **Identity provider**: `KeycloakIdentityProviderPlugin` — issuer/admin URLs,
  clientId/secret, `useUsernameAsOperatonUserId=true`,
  `useGroupPathAsOperatonGroupId=true`, `administratorGroupName`.
- **JWT module**: `/engine-rest` accepts Keycloak Bearer tokens.
- **Webapps disabled**: Cockpit/Tasklist/Admin removed (empty-dir overlay or
  config flag).
- Plugin jars are resolved by the module's Maven/Gradle build (pinned
  versions), not fetched ad-hoc at image-build time.

## Process & worker

- **Models** (ported from `operaton-examples` loan-application, delegates
  removed): `loan-application.bpmn` + `risk-assessment.dmn`, auto-deployed via
  the engine's `/operaton/configuration/resources/` mount.
  - Credit-score service task → **external task**, topic `credit-scoring`.
  - Notification / rejection service tasks → **external task(s)**, topic
    `notification`.
  - DMN business-rule task, exclusive gateway, and the `underwriters`
    user task are unchanged.
  - `candidateStarterGroups` / `candidateGroups` reference Keycloak groups.
- **Worker**: standalone non-Spring Java `main()` using
  `org.operaton:operaton-external-task-client`. Obtains a Keycloak
  service-account token (client-credentials), subscribes to `credit-scoring`
  and `notification`, sets `creditScore` and `loanDecision` variables.
  Containerized; built by the module's Maven + Gradle build.

## Welcome page, Redoc, theming, TLS

- **Welcome page**: hand-built static HTML/CSS served by nginx; itemis-themed;
  two tiles linking to `/control/` and `/tasklist/`.
- **Redoc**: static `redoc` HTML served at exact `/engine-rest`, rendering
  Operaton's OpenAPI spec; `/engine-rest/*` proxies to the engine.
- **Theming**: itemis palette (bright red accent, near-white background, dark
  slate text — exact hexes sampled from itemis.com during implementation),
  applied to the welcome page and a **custom Keycloak login theme**. Flowset
  apps remain stock; README documents how to theme them.
- **TLS**: self-signed certificate for `localhost`, generated by a committed
  shell script (the script is committed; the generated key is git-ignored) and
  mounted into nginx.

## Keycloak seeding (sidecar)

`keycloak-init` runs after Keycloak is healthy and uses `kcadm.sh` (idempotent)
to create:

- Realm (e.g. `operaton`).
- OIDC clients: `oauth2-proxy` (confidential, redirect URIs for the gateway),
  `operaton-engine` (audience for the JWT module), `worker` (service account /
  client-credentials), and any client Flowset Control needs for OIDC.
- Groups: `employees`, `underwriters`, `operaton-admin`.
- Users with human names and group memberships: `alice` (employees), `eve`
  (underwriters), an admin user (operaton-admin). Throwaway dev passwords,
  documented in the README.

The sidecar exits 0 when seeding completes; the rest of the stack depends on it.

## Project structure

```
examples/loan-platform-sso/
├── mvnw, mvnw.cmd, .mvn/wrapper/
├── gradlew, gradlew.bat, gradle/wrapper/
├── pom.xml, build.gradle.kts, settings.gradle.kts
├── docker-compose.yml
├── Dockerfile.engine                     # custom Operaton image
├── README.md                             # 9-section project README
├── docs/arc42/                           # arc42 architecture documentation
├── nginx/                                # nginx config, static welcome + Redoc pages, TLS script
├── keycloak/                             # realm seed script (kcadm) + custom login theme
├── oauth2-proxy/                         # oauth2-proxy config
├── flowset/                              # Control + Tasklist env/config
├── engine/resources/                     # loan-application.bpmn, risk-assessment.dmn
├── engine/config/                        # application.yaml (plugin + JWT + webapps-off)
└── src/
    ├── main/java/org/operaton/examples/loanplatformsso/   # external-task worker
    └── test/java/org/operaton/examples/loanplatformsso/   # smoke + core e2e IT
```

## Build & testing

- **Dual build (Maven + Gradle)**: compiles and packages the worker jar,
  resolves the Keycloak plugin jars as build inputs for the custom engine
  image, and runs the IT (failsafe for Maven, `test` for Gradle).
- **Testing — smoke + core e2e** via Testcontainers: brings up Keycloak +
  `postgres` + the custom engine image + the worker. Asserts:
  1. Realm and users/groups imported (Keycloak reachable, token obtainable).
  2. `loan-application` process and `risk-assessment` decision are deployed.
  3. A client-credentials token authenticates against `/engine-rest`.
  4. Starting the process drives the external-task worker so the instance
     reaches the expected end state for low / medium / high risk (medium pauses
     at the underwriter user task; completing it ends the instance).
  - Browser OIDC, nginx TLS, oauth2-proxy, and the Flowset UIs are **not**
    automated — they are covered by the manual README walkthrough.

## Documentation

- **README.md** — the 9-section project README required by
  `docs/EXAMPLE_STANDARDS.md` (§8): title + business context; what it
  demonstrates; architecture overview; process models (rendered PNG);
  prerequisites; run it; walk through it (login → start from Tasklist → observe
  in Control); how it works; run the tests.
- **docs/arc42/** — arc42-structured architecture documentation (introduction &
  goals, constraints, context & scope, solution strategy, building-block view,
  runtime view, deployment view, crosscutting concepts, architecture decisions,
  risks & technical debt, glossary). Kept up to date as implementation
  proceeds.

## Standards alignment

- The example follows `docs/EXAMPLE_STANDARDS.md` with the platform-shape
  relaxations (container-only / external UIs): no embedded Spring Boot app,
  engine webapps disabled, external operations tooling — analogous to the
  "Shape C" pattern. The single custom Java artifact (the worker) is built and
  tested per the normal rules.
- Operaton namespace only; grep for `camunda` must be empty before completion.
- Dual Maven + Gradle build parity; Testcontainers (PostgreSQL + Keycloak) for
  the IT; no `Thread.sleep` (Awaitility for async worker completion).

## Out of scope

- Flowset Studio (IDE plugin, not deployable).
- Theming the Flowset apps (documented, not implemented).
- Automated browser/UI testing of the OIDC redirect and Flowset apps.
- Per-user OIDC token pass-through from Flowset Control to the engine (Flowset
  uses a per-engine service credential).
- Production hardening (real certificates, secret management, HA).
