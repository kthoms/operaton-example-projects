# 02 Architecture Constraints — loan-platform-sso

## Technical Constraints

| ID | Constraint | Rationale |
|---|---|---|
| TC-01 | **Docker Compose only** — the stack runs exclusively via `docker compose up`. No Kubernetes, no Helm, no standalone application servers. | Keeps the example self-contained and runnable on a developer laptop. |
| TC-02 | **JDK 21** — the worker and integration tests target Java 21 (`--release 21`). | Matches the Operaton Run distribution's embedded JVM and the project standards requirement. |
| TC-03 | **Operaton Run distribution** — the engine image is `operaton/operaton:2.1.1` started with `--rest`. The Cockpit, Tasklist, and Admin WARs are not loaded. | The example demonstrates the REST-only deployment mode. Web-application WARs would conflict with the external-task pattern and Flowset integration. |
| TC-04 | **No Spring in the worker** — `LoanWorker` is a plain Java `main` class with no Spring Boot dependency. | Demonstrates that external-task workers can be implemented in any JVM language/framework, not only Spring Boot. |
| TC-05 | **Dual build parity** — `pom.xml` and `build.gradle.kts` must compile the same sources and run the same tests. Versions must match between both build files. | Repository standard (see `docs/EXAMPLE_STANDARDS.md §3`). |
| TC-06 | **No H2 in integration tests** — all ITs run against real PostgreSQL via Testcontainers. | Repository standard (see `docs/EXAMPLE_STANDARDS.md §5`). |
| TC-07 | **Flowset images are linux/amd64 only** — `docker-compose.yml` specifies `platform: linux/amd64` for `flowset-control` and `flowset-tasklist`. | The Flowset community images are not published as multi-arch; Apple Silicon hosts use Rosetta 2 / QEMU. |
| TC-08 | **BASIC_IDP auth mode on the engine** — Bearer token (`BEARER_OAUTH2`) is not used for `/engine-rest`. | See ADR-001: the Run distribution's `--oauth2` flag cannot be safely combined with `KeycloakIdentityProviderPlugin` (see `docs/arc42/09-architecture-decisions.md`). |

---

## Organizational Constraints

| ID | Constraint | Rationale |
|---|---|---|
| OC-01 | **Dev/demo use case only** — credentials (`admin/admin`, `alice/alice`, etc.) are intentionally weak and documented. | The project is an example, not a production deployment. Production hardening (secrets management, external TLS, etc.) is out of scope. |
| OC-02 | **Throwaway self-signed TLS** — `nginx/gen-cert.sh` generates a self-signed certificate for `localhost`. | Avoids a dependency on a CA or external DNS. Browsers will show a certificate warning that users must accept manually. |
| OC-03 | **Operaton namespace only** — BPMN/DMN extension elements use `http://operaton.org/schema/1.0/bpmn`; Maven coordinates use `org.operaton.*`. | Repository standard (see `AGENTS.md`, rule 3). |
| OC-04 | **No shared parent module** — the project is self-contained; it does not import shared code from other examples. | Repository standard (see `docs/EXAMPLE_STANDARDS.md §1`). |
