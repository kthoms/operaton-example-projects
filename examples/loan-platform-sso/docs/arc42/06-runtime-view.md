# 06 Runtime View — loan-platform-sso

This section describes the two primary runtime scenarios of the system as
verified during the stack bring-up of Task 12.

---

## Login Flow (Browser SSO)

A user who is not yet authenticated and navigates to any protected path goes
through the following sequence:

```
Browser                nginx (SSL 8443)    oauth2-proxy        Keycloak
  |                         |                   |                  |
  |-- GET / (HTTPS) ------->|                   |                  |
  |                         |-- GET /oauth2/auth ->|                |
  |                         |<-- 401 Unauthorized -|                |
  |                         |   (error_page 401 = /oauth2/sign_in) |
  |                         |-- GET /oauth2/sign_in ->|             |
  |                         |<-- 200 Sign-In HTML ----|             |
  |<-- 200 Sign-In page ----|                   |                  |
  |                         |                   |                  |
  | [user clicks "Sign in with Keycloak OIDC"]  |                  |
  |                         |                   |                  |
  |-- GET /oauth2/start ---->|                  |                  |
  |                         |-- GET /oauth2/start ->|              |
  |                         |<-- 302 to Keycloak /auth ---        |
  |<-- 302 Location: http://keycloak:8080/... --|                  |
  |                         |                   |                  |
  |-- GET /realms/operaton/protocol/openid-connect/auth ---------->|
  |<-- 200 Keycloak Login Form -----------------------------------------|
  |                         |                   |                  |
  | [user enters credentials]                   |                  |
  |                         |                   |                  |
  |-- POST credentials ------------------------------------------------->|
  |<-- 302 to https://localhost:8080/oauth2/callback?code=... ----------|
  |                         |                   |                  |
  |-- GET /oauth2/callback?code=... -------->|  |                  |
  |                         |-- GET /oauth2/callback?code=... ->|  |
  |                         |               |-- POST /token ---->|  |
  |                         |               |<-- access_token, id_token|
  |                         |               [oauth2-proxy creates session cookie]
  |                         |<-- 302 to original URL (e.g. /) ---|
  |<-- 302 / Set-Cookie:_oauth2_proxy=... --|                     |
  |                         |                   |                  |
  |-- GET / (with cookie) -->|                  |                  |
  |                         |-- GET /oauth2/auth (cookie) ->|     |
  |                         |<-- 202 Authorized  ----------|      |
  |                         [nginx serves /usr/share/nginx/html/index.html]
  |<-- 200 Welcome Page ----|                   |                  |
```

**Key implementation details:**

- nginx listens on port 8443 (TLS) with a self-signed certificate.
  The Docker Compose host mapping is `8080:8443`, so external clients use port 8080.
- All app routes (`/`, `/control/`, `/tasklist/`, `/engine-rest/`) use
  `auth_request /oauth2/auth` which internally calls oauth2-proxy on port 4180.
- `error_page 401 = /oauth2/sign_in` causes nginx to serve the oauth2-proxy
  sign-in page (not a 302 redirect). The redirect to Keycloak happens when the
  user submits the sign-in form (POST to `/oauth2/start`).
- oauth2-proxy uses PKCE (`code_challenge_method = S256`) for the authorization
  code flow.
- After a successful authentication, oauth2-proxy sets a session cookie
  (`_oauth2_proxy`) which is validated on every subsequent request.
- oauth2-proxy is configured with `set_xauthrequest = true` and
  `pass_access_token = true`, so nginx can extract the access token via
  `$upstream_http_x_auth_request_access_token` and forward it to upstreams.

---

## Process Execution Flow (Loan Application)

A loan application is started either from the Flowset Tasklist UI or via a
direct REST API call. The worker drives the service tasks:

```
Client/Tasklist     Operaton Engine     LoanWorker         DMN Engine
    |                    |                  |                   |
    |-- POST /engine-rest/process-definition/key/loan-application/start
    |   (with loanAmount=100000)            |                   |
    |                    |                  |                   |
    |                    [creates process instance: StartEvent -> Task_CreditScore]
    |<-- 200 {instanceId}|                  |                   |
    |                    |                  |                   |
    |                    |  [external task published: topic=credit-scoring]
    |                    |                  |                   |
    |                    |<-- fetchAndLock (topic=credit-scoring, BASIC auth)
    |                    |-- task locked -->|                   |
    |                    |                  |                   |
    |                    |             [derives creditScore from loanAmount:
    |                    |              amount <= 500000 -> creditScore=720]
    |                    |                  |                   |
    |                    |<-- complete(creditScore=720) --------|
    |                    |                  |                   |
    |                    [engine advances to DMN gateway]       |
    |                    |-- evaluate risk-assessment.dmn ----->|
    |                    |  (input: creditScore=720)            |
    |                    |<-- riskLevel=low -------------------|
    |                    |                  |                   |
    |                    [engine advances to Task_Notification (topic=notification)]
    |                    |<-- fetchAndLock (topic=notification)--|
    |                    |-- task locked -->|                   |
    |                    |                  |                   |
    |                    |             [maps riskLevel to decision:
    |                    |              low -> loanDecision=APPROVED]
    |                    |                  |                   |
    |                    |<-- complete(loanDecision=APPROVED) --|
    |                    |                  |                   |
    |                    [engine reaches End Event: state=COMPLETED]
    |                    |                  |                   |
```

**Verified outcome (Task 12 run):**

| Variable       | Value     | Set by                   |
|----------------|-----------|--------------------------|
| `loanAmount`   | 100000    | Process start input      |
| `creditScore`  | 720       | worker `credit-scoring`  |
| `riskLevel`    | `low`     | DMN `risk-assessment`    |
| `loanDecision` | `APPROVED`| worker `notification`    |

**Worker authentication:**

The `LoanWorker` uses `ENGINE_AUTH_MODE=BASIC_IDP`. It sends HTTP Basic
credentials (`worker:worker`) on every `fetchAndLock` and `complete` request.
The `KeycloakIdentityProviderPlugin` in the engine validates these credentials
against Keycloak's Resource Owner Password Credentials endpoint.

The `worker` user belongs to the `operaton-admin` Keycloak group (seeded by
`keycloak/seed-realm.sh`), which maps to the `operaton-admin` group in the
engine, granting it full access to external task operations.

**DMN evaluation:**

The `risk-assessment.dmn` decision table evaluates `creditScore`:

- `creditScore >= 700` → `riskLevel = low`
- `creditScore < 700` → `riskLevel = high`

A `loanAmount <= 500000` produces `creditScore = 720` (low risk → APPROVED).
A `loanAmount > 500000` produces `creditScore = 580` (high risk → REJECTED).
