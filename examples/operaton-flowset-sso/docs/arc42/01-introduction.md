# 01 Introduction and Goals — operaton-flowset-sso

## Business Context

The **operaton-flowset-sso** example demonstrates a complete, secured loan-origination
platform. A loan applicant (or loan officer) submits a loan request with an amount
through Flowset Tasklist. The system automatically scores the creditworthiness,
evaluates risk via a DMN decision table, and either auto-approves, auto-rejects,
or routes the application to an underwriter for manual review. The final decision
is communicated back through the process.

The platform is the reference architecture for combining Operaton's REST-only Run
distribution with enterprise SSO (Keycloak) and third-party task-management UIs
(Flowset), behind a single HTTP entry point (TLS opt-in).

---

## Quality Goals

| Priority | Quality Goal | Scenario |
|---|---|---|
| 1 | **Security / Single Sign-On** | Every browser-facing endpoint is protected by Keycloak OIDC via oauth2-proxy. Users authenticate once and access all services without re-entering credentials. |
| 2 | **Service decoupling** | Service work (credit scoring, notifications) is performed by an external-task worker that has no compile-time dependency on the engine's Spring context. The worker can be replaced, scaled, or rewritten independently. |
| 3 | **Auditability** | Every process step, variable, and user-task completion is recorded in Operaton's history database and queryable via the REST API and Flowset Control. |
| 4 | **Reproducibility** | A clean checkout + `docker compose up --build` reproduces the full running stack with no manual prerequisites. |
| 5 | **Testability** | The integration test starts the full engine and identity layer via Testcontainers and executes all three risk paths end-to-end without mocks. |

---

## Stakeholders

| Role | Expectations |
|---|---|
| **Loan applicant** (alice) | Submit a loan application via Tasklist; see the outcome. |
| **Underwriter** (eve) | Receive and complete medium-risk review tasks in Tasklist. |
| **Operations admin** (admin) | Monitor all instances and history in Flowset Control. |
| **External-task worker** (worker service account) | Authenticate against the engine with HTTP Basic; fetch and complete service tasks. |
| **Platform developer** | Understand how to wire together Operaton, Flowset, Keycloak, and oauth2-proxy; reuse the stack pattern for their own projects. |
| **Platform engineer** | Understand the nginx/oauth2-proxy SSO gateway pattern; adapt TLS and redirect URIs for production. |
