# 12 Glossary — loan-platform-sso

| Term | Definition |
|---|---|
| **Operaton Run** | The self-contained Spring Boot fat-jar distribution of the Operaton process engine. Started with `./operaton.sh [--rest] [--webapps] [--oauth2]`. In this project, started with `--rest` only (no web-application WARs). |
| **External Task** | A process task whose implementation is handled by a worker process outside the engine. The engine publishes the task; the worker fetches, locks, executes, and completes it. External tasks decouple service implementations from the engine's lifecycle. |
| **External-Task Client** | The `operaton-external-task-client` Java library used by `LoanWorker` to poll the engine's `/engine-rest/external-task/fetchAndLock` endpoint and complete tasks. |
| **BASIC_IDP** | The authentication mode in which the Operaton engine's `/engine-rest` endpoint accepts HTTP Basic credentials and validates them by forwarding them to Keycloak via the ROPC grant type. Configured by `KeycloakIdentityProviderPlugin`. Contrast with `BEARER_OAUTH2`. |
| **BEARER_OAUTH2** | An alternative authentication mode where the engine validates JWT Bearer tokens on `/engine-rest` using a Spring Security resource-server filter (activated via `./operaton.sh --oauth2`). Not used in this project — see ADR-001. |
| **KeycloakIdentityProviderPlugin** | The Operaton Keycloak identity-provider plugin (`operaton-keycloak-all`). Replaces Operaton's built-in LDAP/DB identity service with a Keycloak-backed one. Validates HTTP Basic credentials via Keycloak ROPC and synchronises groups/users. |
| **oauth2-proxy** | An open-source reverse proxy (`quay.io/oauth2-proxy/oauth2-proxy`) that handles OIDC authentication on behalf of application services. In this project it acts as an auth-only sidecar for nginx (`auth_request`). |
| **auth_request** | An nginx directive that delegates authentication for a route to a sub-request to an internal endpoint (here: `oauth2-proxy:4180/oauth2/auth`). If the sub-request returns 2xx, nginx proceeds; otherwise it applies the configured `error_page`. |
| **keycloak-init sidecar** | A second container using the Keycloak image with an overridden entrypoint (`bash /seed/seed-realm.sh`). It runs `kcadm.sh` commands to create the `operaton` realm, clients, groups, and users, then exits with code 0. |
| **PKCE** | Proof Key for Code Exchange. An extension to the OAuth 2.0 Authorization Code flow that protects against authorization code interception attacks. Used by oauth2-proxy (`code_challenge_method = S256`). Eliminates the need for a client secret in the browser leg of the flow. |
| **ROPC** | Resource Owner Password Credentials. An OAuth 2.0 grant type where a client exchanges a username and password directly for an access token. Used by `KeycloakIdentityProviderPlugin` to validate HTTP Basic credentials on every `/engine-rest` request. Deprecated in OAuth 2.1 for public clients but appropriate for trusted backend service accounts. |
| **Flowset Control** | A third-party web application (`flowset/flowset-control-community`) for monitoring Operaton process instances, viewing history, and managing running processes. Connects to Keycloak via OIDC and to the engine via the REST API. |
| **Flowset Tasklist** | A third-party React SPA (`flowset/flowset-tasklist-react-community`) for end users to start process instances and complete user tasks. Calls `/engine-rest` via the nginx proxy. |
| **credit-scoring** | The name of the external-task topic handled by the worker's first handler. Derives a `creditScore` from the `loanAmount` variable (or uses a pre-set value injected by tests). |
| **notification** | The name of the external-task topic handled by the worker's second handler. Maps `riskLevel` to a `loanDecision` value (`APPROVED` or `REJECTED`). |
| **riskLevel** | A process variable set by the DMN decision table `risk-assessment.dmn`. Values: `low` (creditScore ≥ 700), `high` (creditScore < 700), or `medium` (see DMN table). |
| **loanDecision** | A process variable set by the `notification` worker handler. Values: `APPROVED` (for non-high risk) or `REJECTED` (for high risk). |
| **creditScore** | A process variable set by the `credit-scoring` worker handler. A numeric score (e.g., 720 for low-risk, 580 for high-risk) used as input to the DMN decision table. |
| **Testcontainers** | A Java library for starting Docker containers in JUnit tests. Used in `LoanPlatformIT` to start PostgreSQL, Keycloak, and the custom engine image. |
| **Awaitility** | A Java DSL for polling asynchronous conditions in tests without `Thread.sleep`. Used in `LoanPlatformIT` to wait for process instances to reach their expected end events. |
| **Operaton** | The open-source BPMN process engine. Uses the `org.operaton.*` namespace and the `http://operaton.org/schema/1.0/bpmn` extension namespace in BPMN/DMN models. |
