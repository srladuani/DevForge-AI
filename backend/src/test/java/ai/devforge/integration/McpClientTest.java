package ai.devforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.devforge.integration.TrackerException.Kind;
import ai.devforge.integration.internal.mcp.McpClient;
import ai.devforge.testsupport.FakeMcpServer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpClientTest {
    private final FakeMcpServer server = new FakeMcpServer().issue("ABC-1", FakeMcpServer.sampleIssue("ABC-1", "Videos"));
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach void stop() { server.close(); }

    private McpClient client(String secret) { return new McpClient(server.url(), secret, http, Duration.ofSeconds(5)); }

    @Test
    void handshakesThenListsAndCallsTools() {
        var tools = client("tok-valid").listTools();
        assertThat(tools).contains("getJiraIssue", "editJiraIssue");
        assertThat(server.methods()).containsExactly("initialize", "notifications/initialized", "tools/list");
        var issue = client("tok-valid").callTool("getJiraIssue", Map.of("cloudId", "cloud-1", "issueIdOrKey", "ABC-1"));
        assertThat(issue.path("fields").path("summary").asString()).isEqualTo("Videos");
    }

    @Test
    void followsThePaginatedToolListToTheEndSoNoToolIsMissed() {
        server.toolNames = java.util.List.of("a1", "a2", "a3", "a4", "a5", "getJiraIssue", "z9");
        server.pageSize = 2;
        assertThat(client("tok-valid").listTools()).containsExactly("a1", "a2", "a3", "a4", "a5", "getJiraIssue", "z9");
        assertThat(server.methods().stream().filter(m -> m.equals("tools/list")).count()).as("one request per page").isEqualTo(4);
    }

    @Test
    void readsAnEventStreamReplyAndJsonAfterANoticeAndEmailTokenPairsAreSentAsBasic() {
        server.eventStream = true;
        var issue = client("someone@example.test:tok-valid").callTool("getJiraIssue", Map.of("cloudId", "cloud-1", "issueIdOrKey", "ABC-1"));
        assertThat(issue.path("key").asString()).isEqualTo("ABC-1");
        assertThat(server.calls().getFirst().authorization()).startsWith("Basic ");
    }

    @Test
    void aRejectedCredentialIsAnAuthError() {
        assertThatThrownBy(() -> client("wrong").listTools()).isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.kind()).isEqualTo(Kind.AUTH_INVALID));
    }

    @Test
    void writeToolsAreNeverCalledEvenWhenAskedAndNeverReachTheServer() {
        for (String tool : new String[] {"editJiraIssue", "addCommentToJiraIssue", "transitionJiraIssue", "createJiraIssue"}) {
            assertThatThrownBy(() -> client("tok-valid").callTool(tool, Map.of())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.calls()).isEmpty();
        assertThat(server.methods()).isEmpty();
    }

    @Test
    void aMissingTicketIsNotFoundAndJunkIsABadResponseAndServerErrorsAreUnavailable() {
        assertThatThrownBy(() -> client("tok-valid").callTool("getJiraIssue", Map.of("cloudId", "c", "issueIdOrKey", "NOPE-1")))
            .isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.kind()).isEqualTo(Kind.NOT_FOUND));
        server.junkInsteadOfJson = true;
        assertThatThrownBy(() -> client("tok-valid").callTool("getJiraIssue", Map.of("cloudId", "c", "issueIdOrKey", "ABC-1")))
            .isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.kind()).isEqualTo(Kind.BAD_RESPONSE));
        server.failNext(5);
        assertThatThrownBy(() -> client("tok-valid").listTools()).isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }

    @Test
    void anUnreachableServerIsUnavailable() {
        server.close();
        assertThatThrownBy(() -> client("tok-valid").listTools()).isInstanceOfSatisfying(TrackerException.class, e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
    }
}
