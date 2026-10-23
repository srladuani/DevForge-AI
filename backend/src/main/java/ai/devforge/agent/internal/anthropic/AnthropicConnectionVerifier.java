package ai.devforge.agent.internal.anthropic;

import ai.devforge.agent.internal.LiveModels;
import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies a key with one real, free, read-only call (listing the models the key can use) and remembers that list so the model picker only
 * offers what the key can actually run. Only the HTTP status and model ids are used; the key is sent to the configured endpoint and nowhere else.
 */
@Component
@ConditionalOnProperty(name = "devforge.agent.mode", havingValue = "real")
class AnthropicConnectionVerifier implements ConnectionVerifier {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AnthropicEndpoint endpoint;
    private final LiveModels live;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    AnthropicConnectionVerifier(AnthropicEndpoint endpoint, LiveModels live) {
        this.endpoint = endpoint;
        this.live = live;
    }

    @Override
    public boolean supports(ConnectionKind kind, String providerType) { return kind == ConnectionKind.AI_PROVIDER && providerType.equals("ANTHROPIC"); }

    @Override
    public Result verify(String secret) { return verify(null, secret); }

    @Override
    public Result verify(UUID connectionId, String secret) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/v1/models?limit=100")).timeout(Duration.ofSeconds(20))
            .header("x-api-key", secret).header("anthropic-version", "2023-06-01").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            if (status == 200) {
                Set<String> ids = new HashSet<>();
                JsonNode data = JSON.readTree(res.body()).path("data");
                data.forEach(m -> ids.add(m.path("id").asString("")));
                ids.remove("");
                if (connectionId != null && !ids.isEmpty()) live.remember(connectionId, ids);
                return new Result(ConnectionStatus.VALID, "Verified with Anthropic; " + ids.size() + " model(s) available to this key");
            }
            if (status == 401 || status == 403) return new Result(ConnectionStatus.INVALID, "Anthropic rejected this key");
            if (status == 429) return new Result(ConnectionStatus.QUOTA, "Anthropic is rate limiting this key or its quota is used up");
            if (status >= 500) return new Result(ConnectionStatus.UNVERIFIED, "Anthropic is temporarily unavailable; try again");
            return new Result(ConnectionStatus.INVALID, "Anthropic answered HTTP " + status);
        } catch (IOException e) {
            return new Result(ConnectionStatus.UNVERIFIED, "Could not reach Anthropic; check the network and try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(ConnectionStatus.UNVERIFIED, "Verification was interrupted");
        }
    }
}
