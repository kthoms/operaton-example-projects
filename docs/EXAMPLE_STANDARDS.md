# Example Standards — Definition of Done

Every project in this repository MUST satisfy every item below. There are no
exceptions. A project that fails one checklist item is not mergeable.

All numbered sections are binding. The checklist in §10 is a working summary,
not a substitute — passing the checklist does not waive any section.

## 1. Scope

- A project demonstrates **one complete business use case** (named in the
  README's first sentence). Multiple BPMN processes, integrations, and actors
  are expected and encouraged when the use case requires them.
- Minimal: no code, dependency, or model element that does not serve the
  demonstrated use case. If a class can be deleted and the project still
  demonstrates its use case, delete it.
- Self-contained: a project never depends on another project or on a shared
  parent module. Copy, don't share — projects are deployed in isolation.

## 2. Project structure

```
examples/<kebab-case-name>/
├── mvnw, mvnw.cmd, .mvn/wrapper/          # Maven Wrapper (committed)
├── pom.xml
├── gradlew, gradlew.bat, gradle/wrapper/  # Gradle Wrapper (committed)
├── build.gradle.kts, settings.gradle.kts
├── docker-compose.yml                     # only the services this project needs
├── README.md
└── src/
    ├── main/java/org/operaton/examples/<name>/
    ├── main/resources/                    # *.bpmn, *.dmn, application.yaml
    └── test/java/org/operaton/examples/<name>/
```

- Directory name: `kebab-case` (no ordinal prefix — projects have no prescribed reading order).
- Java package: `org.operaton.examples.<name>` where `<name>` is
  the directory name with hyphens removed (`order-fulfillment` → `orderfulfillment`).
- Maven coordinates: groupId `org.operaton.examples`,
  artifactId = directory name, version `0.1.0-SNAPSHOT`.

## 3. Dual build — Maven AND Gradle

- `./mvnw verify` and `./gradlew build` MUST both succeed from a clean
  checkout with only JDK 21 and Docker installed.
- Both builds compile the same `src/` tree and run the same tests. Gradle's
  `test` task discovers `*IT` classes via the JUnit Platform regardless of
  name; Maven runs them ONLY through failsafe — therefore `pom.xml` MUST
  declare `maven-failsafe-plugin` with the `integration-test` and `verify`
  goals. A green `./mvnw verify` that executed zero ITs is a broken build:
  reviewers check failsafe's `Tests run:` count is > 0.
- Versions (Java, Spring Boot, Operaton) MUST be identical in `pom.xml` and
  `build.gradle.kts`, and MUST match the table in the root README — the single source of truth for pinned versions.
- Dependency management via BOMs only: `spring-boot-dependencies` /
  `SpringBootPlugin.BOM_COORDINATES` plus `org.operaton.bpm:operaton-bom`.
  Never pin a version that a BOM already manages.

## 4. BPMN / DMN models

- Executable semantics use
  `xmlns:operaton="http://operaton.org/schema/1.0/bpmn"` — never the
  `camunda` namespace.
- Every process: `operaton:historyTimeToLive` set (default `P30D`),
  `isExecutable="true"`, process `id` in kebab-case matching the file name.
- Every element has a meaningful `name` (verb-object for tasks: "Submit loan
  application"). Sequence flows out of gateways are named with their
  condition ("approved", "rejected", "pending review").
- Diverging exclusive gateways: every non-default outgoing flow has a
  `conditionExpression`; exactly one default flow is marked.
- Models include full BPMN DI (`bpmndi:BPMNDiagram`).
- User tasks use `operaton:candidateGroups` (not hard-coded assignees).
- Service tasks use `operaton:delegateExpression="${beanName}"` referencing a
  Spring bean, unless the project demonstrates otherwise.
- DMN: decision `id` in kebab-case matching the file name; hit policy is
  a deliberate choice explained in the README.

## 5. Testing — Testcontainers, end-to-end

- Integration tests are named `*IT` and live in `src/test/java`.
- Every IT class runs against **PostgreSQL via Testcontainers**
  (`@Testcontainers` + `@Container` + `@ServiceConnection`). H2 is forbidden
  in integration tests.
- External systems the project integrates with are ALSO started via
  Testcontainers — the test must exercise the real integration.
- Tests execute the process end-to-end: deploy → start → drive through wait
  states → assert it ended in the expected end event.
- Both happy path and at least one alternative/error path are tested.
- No `Thread.sleep` — use Awaitility for asynchronous continuations.
- `./mvnw verify` runs the ITs via failsafe; `./gradlew build` runs them via
  the standard `test` task.

## 6. Docker Compose (local exploration)

- `docker-compose.yml` contains exactly the services needed to run the
  project locally (always PostgreSQL; plus the project's external systems).
- Every service has a `healthcheck`; dependent services use
  `depends_on: condition: service_healthy`.
- Fixed, documented host ports; credentials are throwaway dev values stated
  in the README.
- `docker compose up -d` followed by `./mvnw spring-boot:run` MUST work with
  zero manual configuration.

## 7. Application conventions

- Spring Boot 4, single `@SpringBootApplication` class named
  `<Name>Application`.
- `application.yaml` (not `.properties`); datasource points at the
  docker-compose PostgreSQL; an admin user `demo/demo` is configured via
  `operaton.bpm.admin-user`.
- Additional users/groups seeded idempotently, using human names (`alice`,
  `bob`), never `user1`.
- Delegates are complete, runnable implementations — never stubs that log
  "TODO".

## 8. Documentation

Every project README contains, in this order:

1. **Title + business context** — what real-world problem this solves.
2. **What this project demonstrates** — 3–7 bullets.
3. **Architecture overview** — components, integrations, actors (prose or
   diagram).
4. **Process models** — one PNG per BPMN, rendered via `scripts/render-bpmn.sh`
   and referenced as `![diagram](src/main/resources/<name>.png)`. Commit the
   PNG alongside the README. Register the PNG path in `.operaton-starter.yml`
   under `screenshots`. Prerequisites: `npm install -g bpmn-to-image`.
5. **Prerequisites** — JDK 21, Docker; exact versions.
6. **Run it** — `docker compose up -d`, then both `./mvnw spring-boot:run`
   and `./gradlew bootRun`; URLs and credentials (http://localhost:8080,
   demo/demo).
7. **Walk through it** — full scenario narrative covering the happy path and
   at least one alternative/error path (Tasklist clicks and/or curl commands).
8. **How it works** — prose linking architecture elements to code (file links,
   not code dumps).
9. **Run the tests** — `./mvnw verify` and `./gradlew build`; one sentence on
   what the ITs prove.

## 9. Quality gate (CI)

- CI builds every project with BOTH wrappers on every push/PR; a red project
  blocks merge.
- Adding a project = adding its directory and registering it in the root
  `pom.xml` and `settings.gradle.kts`; CI discovers it automatically.

## 10. Review checklist (copy into every project PR)

```
- [ ] ./mvnw verify passes from clean checkout (failsafe ran > 0 ITs)
- [ ] ./gradlew build passes from clean checkout
- [ ] docker compose up -d && ./mvnw spring-boot:run works, Cockpit reachable
- [ ] BPMN/DMN use operaton namespace, have DI, names, historyTimeToLive
- [ ] ITs use Testcontainers (PostgreSQL + real integrations), no H2, no sleeps
- [ ] Happy path + alternative path tested end-to-end
- [ ] README has all 9 sections; PNGs rendered via render-bpmn.sh and referenced
- [ ] Versions match pom.xml == build.gradle.kts == root README table
- [ ] §7 app conventions: demo/demo admin user, named seed users, application.yaml
- [ ] No dead code, no unused dependencies, no TODO/stub delegates
```
