# 04 Solution Strategy — operaton-flowset-sso

## Key Architectural Decisions

### 1. Edge OIDC Proxy Pattern

**Problem:** Multiple application components (welcome page, Flowset Control,
Flowset Tasklist) need SSO protection, but they are independent containers that
cannot share session state.

**Solution:** All browser traffic enters through a single nginx TLS endpoint.
Every request is validated by an `auth_request` sub-request to oauth2-proxy.
oauth2-proxy maintains the OIDC session in a cookie and forwards the Keycloak
access token to upstreams via HTTP headers. Application containers do not
implement authentication themselves — they receive pre-authenticated requests
from nginx.

**Benefit:** Any new service added to nginx gains SSO protection automatically.
The authentication logic is centralised in two components (`nginx` + `oauth2-proxy`).

---

### 2. External-Task Worker in Plain Java

**Problem:** Service tasks (credit scoring, notifications) need to be executed
by business logic outside the process engine. A Spring Boot dependency in the
worker would bloat the example and obscure the core pattern.

**Solution:** `LoanWorker` is a plain Java `main` class that uses
`operaton-external-task-client` directly. It has no Spring context, no
dependency injection framework, and no embedded web server.

**Benefit:** The worker demonstrates the minimum viable integration footprint.
It can be replaced by a worker written in any JVM language (Kotlin, Scala) or
ported to a non-JVM language using the Operaton REST API directly.

---

### 3. Keycloak as Identity Provider (BASIC_IDP Mode)

**Problem:** The Operaton Run distribution starts with `--rest`, which disables
the web-application WARs and their Spring Security bean wiring. The JWT/Bearer
resource-server module (`operaton-keycloak-rest`) depends on those beans and
cannot be activated.

**Solution:** Use `KeycloakIdentityProviderPlugin` (`operaton-keycloak-all`) as
the engine identity service. This plugin validates HTTP Basic credentials for
every `/engine-rest` request against Keycloak's ROPC endpoint. No Spring Security
wiring is required. See ADR-001 in `docs/arc42/09-architecture-decisions.md` for
the full analysis.

**Benefit:** A deterministic, documented authentication path that works with the
Run distribution. The plugin and the ROPC validation are the only Keycloak
integration points on the engine side.

---

### 4. Dual Build (Maven + Gradle)

**Problem:** Different teams prefer different build tools; the repository standard
requires both to work from a clean checkout.

**Solution:** `pom.xml` and `build.gradle.kts` compile the same `src/` tree.
Both declare identical versions. `pom.xml` configures `maven-failsafe-plugin`
to run `*IT` integration tests; `build.gradle.kts` uses `useJUnitPlatform()`.

---

### 5. Testcontainers for Integration Tests

**Problem:** The integration test must validate the real Keycloak authentication
path and the real engine deployment without requiring the full Docker Compose
stack to be running.

**Solution:** `LoanPlatformIT` starts PostgreSQL (×2), Keycloak, and the custom
engine image via Testcontainers on a shared Docker network. Realm seeding runs
via `execInContainer` against the live Keycloak container. The worker starts
in-process against the mapped engine port.

**Benefit:** The IT is self-contained: `./mvnw verify` or `./gradlew build` from
a clean checkout with only JDK 21 and Docker is sufficient. No external services
are required.

---

### 6. Keycloak Init Sidecar

**Problem:** Keycloak must be configured with the `operaton` realm, clients, groups,
and users before any other service starts. Bundling this into the Keycloak image
would tightly couple configuration to the image lifecycle.

**Solution:** A second container (`keycloak-init`) uses the same Keycloak image but
overrides the entrypoint to run `keycloak/seed-realm.sh` via `kcadm.sh`. Docker
Compose's `condition: service_completed_successfully` ensures downstream services
only start after seeding succeeds. The sidecar runs `restart: "no"` so it exits
after the first successful run.

**Benefit:** Seeding logic is in a plain shell script that is easy to read, modify,
and reproduce in integration tests (`execInContainer`).
