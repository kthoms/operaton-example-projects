package org.operaton.examples.loanplatformsso;

import org.operaton.bpm.client.ExternalTaskClient;
import org.operaton.bpm.client.interceptor.ClientRequestInterceptor;

import java.util.Base64;
import java.util.Map;

/**
 * Non-Spring external-task worker. Handles the loan-application service work:
 *  - topic "credit-scoring": derive a creditScore from loanAmount
 *  - topic "notification":   set loanDecision (APPROVED/REJECTED) from riskLevel
 */
public class LoanWorker {

    public static void main(String[] args) {
        String baseUrl   = env("ENGINE_REST_URL", "http://operaton:8080/engine-rest");
        String authMode  = env("ENGINE_AUTH_MODE", "BASIC_IDP");
        String user      = env("ENGINE_USER", "worker");
        String password  = env("ENGINE_PASSWORD", "worker");

        ClientRequestInterceptor auth;
        if ("BEARER_OAUTH2".equals(authMode)) {
            KeycloakTokenProvider tokens = new KeycloakTokenProvider(
                env("KEYCLOAK_TOKEN_URL", "http://keycloak:8080/realms/operaton/protocol/openid-connect/token"),
                env("WORKER_CLIENT_ID", "worker"),
                env("WORKER_CLIENT_SECRET", "worker-secret"));
            auth = ctx -> ctx.addHeader("Authorization", "Bearer " + tokens.accessToken());
        } else { // BASIC_IDP
            String basic = Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes());
            auth = ctx -> ctx.addHeader("Authorization", "Basic " + basic);
        }

        ExternalTaskClient client = ExternalTaskClient.create()
            .baseUrl(baseUrl)
            .addInterceptor(auth)
            .asyncResponseTimeout(10_000)
            .build();

        client.subscribe("credit-scoring")
            .lockDuration(10_000)
            .handler((task, service) -> {
                Integer loanAmount = task.getVariable("loanAmount");
                // Pre-set creditScore wins (used by tests); otherwise derive from amount.
                Integer creditScore = task.getVariable("creditScore");
                if (creditScore == null) {
                    creditScore = (loanAmount != null && loanAmount > 500000) ? 580 : 720;
                }
                service.complete(task, Map.of("creditScore", creditScore), Map.of());
            })
            .open();

        client.subscribe("notification")
            .lockDuration(10_000)
            .handler((task, service) -> {
                String riskLevel = task.getVariable("riskLevel");
                String decision = "high".equals(riskLevel) ? "REJECTED" : "APPROVED";
                service.complete(task, Map.of("loanDecision", decision), Map.of());
            })
            .open();

        System.out.println("LoanWorker started (authMode=" + authMode + ", baseUrl=" + baseUrl + ")");
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
