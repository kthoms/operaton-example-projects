package org.operaton.examples.loanplatformsso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Obtains a Keycloak access token via the client-credentials grant. */
public class KeycloakTokenProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final HttpClient http = HttpClient.newHttpClient();

    public KeycloakTokenProvider(String tokenUrl, String clientId, String clientSecret) {
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String accessToken() {
        String form = "grant_type=client_credentials"
            + "&client_id=" + enc(clientId)
            + "&client_secret=" + enc(clientSecret);
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("Token request failed: HTTP " + resp.statusCode());
            }
            return parseAccessToken(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("Could not obtain Keycloak token", e);
        }
    }

    static String parseAccessToken(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.get("access_token").asText();
        } catch (Exception e) {
            throw new IllegalStateException("Malformed token response", e);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
