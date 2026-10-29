package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import ai.devforge.integration.TrackerException;
import ai.devforge.integration.internal.mcp.McpClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Verifies a tracker credential with a real MCP handshake and tool listing; nothing is read from or written to any ticket. */
@Component
class JiraConnectionVerifier implements ConnectionVerifier {
    private final JiraTrackers jira;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final OAuthTokens oauthTokens;
    private final org.springframework.beans.factory.ObjectProvider<ai.devforge.identity.Connections> connections;

    JiraConnectionVerifier(JiraTrackers jira, OAuthTokens oauthTokens, org.springframework.beans.factory.ObjectProvider<ai.devforge.identity.Connections> connections) {
        this.jira = jira;
        this.oauthTokens = oauthTokens;
        this.connections = connections;
    }

    @Override
    public Result verify(java.util.UUID connectionId, java.util.UUID ownerUserId, String secret) {
        if (!ai.devforge.integration.internal.oauth.OAuthSecret.looksLikeOAuth(secret)) return verify(secret);
        try {
            var credential = new ai.devforge.integration.TrackerCredential(connectionId, ownerUserId, () -> secret, s -> connections.getObject().replaceSecret(connectionId, ownerUserId, s));
            return check(oauthTokens.accessToken(credential), true);
        } catch (TrackerException e) {
            return new Result(ConnectionStatus.INVALID, e.getMessage());
        }
    }

    @Override
    public boolean supports(ConnectionKind kind, String providerType) { return kind == ConnectionKind.TRACKER && JiraTrackers.TYPE.equals(providerType); }

    @Override
    public Result verify(String secret) {
        if (ai.devforge.integration.internal.oauth.OAuthSecret.looksLikeOAuth(secret)) return check(ai.devforge.integration.internal.oauth.OAuthSecret.parse(secret).accessToken(), true);
        return check(secret, false);
    }

    private Result check(String credential, boolean bearer) {
        try {
            var tools = new McpClient(jira.endpoint(), credential, http, Duration.ofSeconds(20), bearer).listTools();
            if (!tools.contains("getJiraIssue")) {
                // the credential was accepted; what is missing is the ticket-reading tool, so say what the server does offer (tool names are not secret)
                String offered = String.join(", ", tools.stream().limit(25).toList());
                return new Result(ConnectionStatus.INVALID, "The tracker accepted this credential, but its tool list has no `getJiraIssue`, which DevForge needs to read tickets. Tools offered ("
                    + tools.size() + "): " + (offered.length() > 600 ? offered.substring(0, 600) + "…" : offered));
            }
            return new Result(ConnectionStatus.VALID, "Connected to the tracker; ticket reading is available (" + tools.size() + " tools offered, only read-only ones are ever used)");
        } catch (TrackerException e) {
            return switch (e.kind()) {
                case AUTH_INVALID -> new Result(ConnectionStatus.INVALID, "The tracker rejected this credential");
                case UNAVAILABLE -> new Result(ConnectionStatus.UNVERIFIED, "Could not reach the tracker; try again");
                default -> new Result(ConnectionStatus.UNVERIFIED, "The tracker answered in an unexpected way: " + e.getMessage());
            };
        }
    }
}
