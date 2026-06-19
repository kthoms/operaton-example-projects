# Design: operaton-example-projects Repository Setup

**Date:** 2026-06-19
**Status:** Approved

## Context

`operaton-example-projects` is a sibling repository to `operaton-examples`. Where
`operaton-examples` contains minimal, single-concept demonstrations, this repo holds
comprehensive, end-to-end use-case applications. The same infrastructure pattern is
used (dual Maven+Gradle build, Testcontainers ITs, Docker Compose, CI), but the
quality bar and documentation format are adapted for more complex projects.

The repository currently contains only a `LICENSE` file. This setup establishes
everything needed to add the first example project.

## Chosen Approach

**Full mirror upfront**: set up all infrastructure now with an empty `examples/`
folder. Adding the first project means only creating its directory and registering
it in the aggregator — no infrastructure work needed at that point.

## Repository Structure

```
operaton-example-projects/
├── .editorconfig
├── .gitignore
├── .operaton-starter.yml
├── .github/
│   ├── auto-merge.config.yml
│   └── workflows/
│       ├── auto-merge.yml
│       └── ci.yml
├── docs/
│   └── EXAMPLE_STANDARDS.md
├── examples/                             # empty initially
├── scripts/
│   └── render-bpmn.sh
├── gradle/wrapper/
├── .mvn/wrapper/
├── build.gradle.kts
├── gradlew / gradlew.bat
├── mvnw / mvnw.cmd
├── pom.xml
├── settings.gradle.kts
├── AGENTS.md
├── CLAUDE.md
├── LICENSE
└── README.md
```

## Build Setup

Root build is a pure aggregator — no shared code, no parent POM inheritance.

- **`pom.xml`**: groupId `org.operaton.examples`, artifactId
  `operaton-example-projects-aggregate`, version `0.1.0-SNAPSHOT`, packaging
  `pom`. Empty `<modules>` block. Includes Rancher Desktop profile for
  `DOCKER_HOST` socket override (copied from `operaton-examples`).
- **`settings.gradle.kts`**: `rootProject.name =
  "operaton-example-projects-aggregate"`, no `include()` entries yet.
- **`build.gradle.kts`**: empty root build, delegates to subprojects.
- **Wrappers**: Maven Wrapper 3.9.12, Gradle Wrapper 9.2.0 — binary files
  copied from `operaton-examples` (same pinned versions).

**Pinned stack** (recorded in root README version table, same as `operaton-examples`):

| Tool | Version |
|---|---|
| JDK | 21 |
| Spring Boot | 4.1.0 |
| Operaton | 2.1.1 |
| Maven Wrapper | 3.9.12 |
| Gradle Wrapper | 9.2.0 |
| PostgreSQL | 16 |
| Distribution images | 2.1.1 |

## CI Workflow

**`ci.yml`** — three jobs:

1. `discover`: diffs changed files vs `origin/main`, extracts affected example
   paths under `examples/` (handles `use-cases/` nesting). Falls back to all
   examples when nothing changed.
2. `build-maven`: matrix over changed examples, runs `./mvnw -B -ntp verify`
   inside `examples/<name>`.
3. `build-gradle`: matrix over a whitelist of Gradle-enabled examples. Starts
   empty; grows as examples are added and verified with Gradle.

**`auto-merge.yml`** and **`auto-merge.config.yml`**: copied verbatim from
`operaton-examples`. Daily cron at 04:00 UTC; auto-merges eligible Dependabot
PRs after all checks pass.

## Documentation

### EXAMPLE_STANDARDS.md

Same binding rules as `operaton-examples` with the following changes:

**§1 Scope (changed):** A project demonstrates a complete business use case.
Multiple BPMN processes, integrations, and actors are expected. There is no
"one concept" constraint.

**§2 Structure (changed):** Directory name is `kebab-case` with no ordinal
prefix. Project ordering has no prescribed reading order.

**§8 Documentation (changed):** README sections for comprehensive projects:

1. Title + business context (what real-world problem this solves)
2. What this project demonstrates (3–7 bullets)
3. Architecture overview (components, integrations, actors)
4. Process models (one PNG per BPMN, rendered via `scripts/render-bpmn.sh`)
5. Prerequisites (JDK 21, Docker; exact versions)
6. Run it (`docker compose up -d`, then `./mvnw spring-boot:run` and
   `./gradlew bootRun`; URLs and credentials)
7. Walk through it (full scenario narrative — happy path + at least one
   alternative/error path)
8. How it works (prose linking architecture elements to code, with file links)
9. Run the tests (`./mvnw verify` and `./gradlew build`; what the ITs prove)

All other sections (§3–§7, §9–§10) apply unchanged.

### CLAUDE.md

```
Read and follow AGENTS.md and docs/EXAMPLE_STANDARDS.md before any work
in this repository. They define binding quality gates for all projects.
```

### AGENTS.md

Same structure as `operaton-examples/AGENTS.md` with adaptations:
- Framing: projects demonstrate complete business use cases, not single concepts.
- Reference example: the first project added becomes the canonical shape.
- Same non-negotiable rules: Operaton namespace, dual build parity,
  Testcontainers with real systems, TDD, minimalism.

### README.md

- Intro paragraph framing this as comprehensive use-case applications.
- Version table (same pinned stack).
- Empty catalog table (headers + empty body — populated as projects are added).
- "Anatomy of every project" section (mirrors `operaton-examples` Mermaid diagram).
- Link to `docs/EXAMPLE_STANDARDS.md` and `AGENTS.md`.

### .operaton-starter.yml

Same YAML structure as `operaton-examples/.operaton-starter.yml`:
- `repository.name`: `"Operaton Example Projects"`
- `repository.description`: updated to reflect use-case focus
- `examples:` list: empty initially

## What Is NOT in Scope Here

- The first example project — that is a separate task after setup is complete.
- Any shared parent POM or library module — examples are self-contained.
- The `docs/superpowers/plans/` directory — created by the writing-plans skill.
