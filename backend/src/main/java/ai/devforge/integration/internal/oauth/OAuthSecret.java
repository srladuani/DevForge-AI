package ai.devforge.integration.internal.oauth;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What is stored (encrypted, as one vault secret) for a connection made by signing in with a provider. */
public record OAuthSecret(String accessToken, String refreshToken, long expiresAtEpochSecond, String clientId, String tokenEndpoint, String resource, String provider) {
    /** Tokens that never expire (a provider that sends no lifetime and no refresh token) are kept as valid until far in the future. */
    public static final long NEVER = 4_102_444_800L;

    /** Secrets stored before providers were recorded all came from Jira. */
    public OAuthSecret(String accessToken, String refreshToken, long expiresAtEpochSecond, String clientId, String tokenEndpoint, String resource) {
        this(accessToken, refreshToken, expiresAtEpochSecond, clientId, tokenEndpoint, resource, "JIRA_MCP");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static boolean looksLikeOAuth(String secret) { return secret != null && secret.startsWith("{") && secret.contains("\"oauth\""); }

    public static OAuthSecret parse(String secret) {
        JsonNode n = JSON.readTree(secret);
        return new OAuthSecret(n.path("access_token").asString(""), n.path("refresh_token").asString(""), n.path("expires_at").asLong(0), n.path("client_id").asString(""),
            n.path("token_endpoint").asString(""), n.path("resource").asString(""), n.path("provider").asString("JIRA_MCP"));
    }

    public String toJson() {
        return JSON.writeValueAsString(java.util.Map.of("type", "oauth", "access_token", accessToken, "refresh_token", refreshToken, "expires_at", expiresAtEpochSecond, "client_id", clientId,
            "token_endpoint", tokenEndpoint, "resource", resource, "provider", provider));
    }

    public boolean expiresWithin(long seconds) { return Instant.now().plusSeconds(seconds).getEpochSecond() >= expiresAtEpochSecond; }

    @Override public String toString() { return "OAuthSecret[***]"; }
}
