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

    JiraConnectionVerifier(JiraTrackers jira) { this.jira = jira; }

    @Override
    public boolean supports(ConnectionKind kind, String providerType) { return kind == ConnectionKind.TRACKER && JiraTrackers.TYPE.equals(providerType); }

    @Override
    public Result verify(String secret) {
        try {
            var tools = new McpClient(jira.endpoint(), secret, http, Duration.ofSeconds(20)).listTools();
            if (!tools.contains("getJiraIssue")) return new Result(ConnectionStatus.INVALID, "Connected, but this server does not offer the tool needed to read tickets");
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
