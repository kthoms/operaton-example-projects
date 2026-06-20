# Architecture Decisions — operaton-flowset-sso

## ADR-001: Engine REST authentication mode

### Status

Accepted

### Context

`operaton/operaton` is the Run distribution (a self-contained Spring Boot fat-jar
with an embedded Tomcat). It boots with `./operaton.sh --rest`, which activates
only the engine REST API (`/engine-rest/**`) — it does NOT load the Cockpit,
Tasklist, or Admin web-application WARs.

The Keycloak JWT / resource-server module (`operaton-keycloak-rest`) targets the
webapp REST layer and requires Spring Security bean wiring that is only present
when the web-application WARs are loaded. On the Run image those beans are
absent, so `operaton-keycloak-rest` cannot be activated.

The `KeycloakIdentityProviderPlugin` (the *identity-service* plugin, bundled as
`operaton-keycloak-all`) is a different artefact: it replaces Operaton's default
LDAP/DB identity service with a Keycloak-backed one. It hooks into the engine
at the `IdentityProvider` SPI level — no Spring Security dependency — and
validates HTTP Basic credentials for every `/engine-rest` request against
Keycloak's Resource Owner Password Credentials (ROPC) endpoint. This mechanism
works on the Run distribution.

The `--oauth2` flag passed to `./operaton.sh` enables a built-in Spring Security
resource-server filter that accepts JWT Bearer tokens for `/engine-rest`. A
static probe was performed:

- The `operaton/operaton:2.1.1` startup script accepts `--oauth2` as a CLI flag.
- However, enabling `--oauth2` requires a reachable OIDC discovery endpoint at
  startup; without a live Keycloak the engine fails to start.
- More critically, `--oauth2` and `KeycloakIdentityProviderPlugin` target
  independent auth layers: the resource-server filter applies to the REST
  transport, while the identity plugin applies to the engine identity service.
  Using both simultaneously creates conflicting auth semantics on `/engine-rest`.
- The Flowset Tasklist component (used in this project) authenticates to the
  engine on behalf of the end-user using the user's Keycloak credentials via
  HTTP Basic — this fits BASIC_IDP exactly.
- External process workers also authenticate to the engine using a dedicated
  Keycloak service account via HTTP Basic.

Conclusion from static analysis and the operaton-keycloak documentation:
`BASIC_IDP` is the correct and only viable auth mode for `/engine-rest` on the
Run distribution when `KeycloakIdentityProviderPlugin` is active.

### Decision

```
ENGINE_AUTH_MODE = BASIC_IDP
```

`/engine-rest` uses HTTP Basic authentication. Credentials are validated by
`KeycloakIdentityProviderPlugin` against Keycloak's ROPC endpoint. Bearer token
(`BEARER_OAUTH2`) authentication is **not** activated on the Run distribution
because the `--oauth2` flag cannot be safely combined with the identity-provider
plugin in this topology.

Browser-facing SSO for the welcome page and Flowset apps is handled by
`oauth2-proxy` (sitting in front of those services), which is independent of
the engine's `/engine-rest` auth mechanism.

### Consequences

- **Workers** authenticate with HTTP Basic using a dedicated Keycloak service
  account (`worker-service` user in the `operaton` realm). Credentials are
  passed as environment variables to the worker container.
- **Flowset Tasklist** authenticates to the engine with the end-user's Keycloak
  username and password via HTTP Basic. This is the one point in the system
  where the user's credential is forwarded to the engine.
- **oauth2-proxy** provides SSO for the welcome page and Flowset frontends;
  the OIDC session is separate from and does not replace the engine Basic auth.
- Tasks 4, 8, and 13 consume `ENGINE_AUTH_MODE = BASIC_IDP` to configure the
  worker auth headers and the Flowset Tasklist engine connector.

### Probe evidence

Image build confirmed `operaton-keycloak-all-2.1.0.jar` present at
`/operaton/configuration/userlib/operaton-keycloak-all.jar` in
`operaton-flowset-sso-engine:local`.

Static analysis of the Run distribution and `KeycloakIdentityProviderPlugin`
documentation confirms BASIC_IDP as the operative auth mechanism. A live
end-to-end Keycloak probe was not performed in this spike; the determination
is based on the architecture of the Run distribution and is consistent with the
documented behaviour of the plugin (see
https://github.com/operaton/operaton-keycloak for authoritative reference).
