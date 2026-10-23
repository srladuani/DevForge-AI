package ai.devforge.integration.internal.mcp;

import ai.devforge.integration.TrackerException;
import ai.devforge.integration.TrackerException.Kind;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A minimal MCP client over Streamable HTTP: initialize, notifications/initialized, tools/list and tools/call. One session per client
 * instance, which callers keep short-lived (one fetch). Responses may be plain JSON or an event stream; both are handled.
 *
 * <p>Only an allowlist of read-only tools can be called, enforced here whatever a caller or a model asks for. Everything that comes
 * back is untrusted data: it is parsed, never executed, and failures are mapped to {@link TrackerException} kinds.
 */
public final class McpClient {
    /** The only tools DevForge will call on a tracker server. Write-like tools are unreachable by construction. */
    public static final Set<String> READ_ONLY_TOOLS = Set.of("getJiraIssue", "searchJiraIssuesUsingJql", "getAccessibleAtlassianResources", "atlassianUserInfo");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY = 2_000_000;

    private final URI endpoint;
    private final String authorization;
    private final HttpClient http;
    private final Duration timeout;
    private String sessionId;
    private int nextId = 1;
    private boolean initialized;

    public McpClient(String endpoint, String secret, HttpClient http, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.authorization = authorizationFor(secret);
        this.http = http;
        this.timeout = timeout;
    }

    /** {@code email:token} is sent as Basic (Atlassian API tokens); anything else as a Bearer token. */
    static String authorizationFor(String secret) {
        String s = secret.strip();
        return s.contains(":") ? "Basic " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) : "Bearer " + s;
    }

    public List<String> listTools() {
        initialize();
        JsonNode result = request("tools/list", Map.of());
        List<String> names = new ArrayList<>();
        result.path("tools").forEach(t -> names.add(t.path("name").asString("")));
        names.remove("");
        return names;
    }

    /** Calls an allowlisted tool and returns the JSON found in its text output. */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        if (!READ_ONLY_TOOLS.contains(name)) throw new IllegalArgumentException("Tool is not on the read-only allowlist: " + name);
        initialize();
        JsonNode result = request("tools/call", Map.of("name", name, "arguments", arguments));
        StringBuilder text = new StringBuilder();
        result.path("content").forEach(c -> { if ("text".equals(c.path("type").asString(""))) text.append(c.path("text").asString("")).append('\n'); });
        if (result.path("isError").asBoolean(false)) throw toolError(text.toString());
        return extractJson(text.toString());
    }

    private void initialize() {
        if (initialized) return;
        JsonNode result = request("initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "devforge", "version", "1")));
        if (!result.isObject()) throw new TrackerException(Kind.BAD_RESPONSE, "The tracker server did not answer the MCP handshake");
        initialized = true;
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), false);
    }

    private JsonNode request(String method, Map<String, Object> params) {
        int id = nextId++;
        HttpResponse<String> res = send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params), true);
        JsonNode message = findResponse(res, id);
        if (message.has("error")) {
            String msg = message.path("error").path("message").asString("");
            int code = message.path("error").path("code").asInt(0);
            throw new TrackerException(code == 401 || code == 403 ? Kind.AUTH_INVALID : Kind.BAD_RESPONSE, "Tracker server reported an error: " + truncate(msg));
        }
        return message.path("result");
    }

    private HttpResponse<String> send(Map<String, Object> body, boolean expectBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", authorization)
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        try {
            HttpResponse<String> res = http.send(b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 401 || s == 403) throw new TrackerException(Kind.AUTH_INVALID, "The tracker rejected the credential");
            if (s == 404 && sessionId != null) throw new TrackerException(Kind.UNAVAILABLE, "The tracker session expired");
            if (s == 429 || s >= 500) throw new TrackerException(Kind.UNAVAILABLE, "The tracker is unavailable (HTTP " + s + ")");
            if (s >= 400) throw new TrackerException(Kind.BAD_RESPONSE, "The tracker answered HTTP " + s);
            res.headers().firstValue("Mcp-Session-Id").ifPresent(v -> sessionId = v);
            if (expectBody && res.body() != null && res.body().length() > MAX_BODY) throw new TrackerException(Kind.BAD_RESPONSE, "The tracker response was too large");
            return res;
        } catch (IOException e) {
            throw new TrackerException(Kind.UNAVAILABLE, "Could not reach the tracker");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TrackerException(Kind.UNAVAILABLE, "The tracker request was interrupted");
        }
    }

    private static JsonNode findResponse(HttpResponse<String> res, int id) {
        String body = res.body() == null ? "" : res.body();
        String type = res.headers().firstValue("Content-Type").orElse("");
        try {
            if (type.contains("text/event-stream")) {
                for (String line : body.split("\r?\n")) {
                    if (!line.startsWith("data:")) continue;
                    JsonNode n = JSON.readTree(line.substring(5).strip());
                    if (n.path("id").asInt(-1) == id) return n;
                }
                throw new TrackerException(Kind.BAD_RESPONSE, "The tracker stream held no answer to the request");
            }
            JsonNode n = JSON.readTree(body);
            if (n.isArray()) for (JsonNode m : n) if (m.path("id").asInt(-1) == id) return m;
            if (n.isObject() && n.path("id").asInt(-1) == id) return n;
            throw new TrackerException(Kind.BAD_RESPONSE, "The tracker answered something other than the request");
        } catch (TrackerException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TrackerException(Kind.BAD_RESPONSE, "The tracker response was not valid JSON");
        }
    }

    /** Tool output can carry a notice before the JSON; take the first JSON object or array found and treat the rest as data we ignore. */
    static JsonNode extractJson(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') continue;
            try (var parser = JSON.createParser(text.substring(i))) {
                return JSON.readTree(parser);
            } catch (RuntimeException e) {
                // not JSON starting here (e.g. a bracketed notice); keep scanning
            }
        }
        throw new TrackerException(Kind.BAD_RESPONSE, "The tracker returned no JSON data");
    }

    private static TrackerException toolError(String text) {
        String t = text.toLowerCase();
        if (t.contains("401") || t.contains("unauthorized") || t.contains("forbidden") || t.contains("403") && !t.contains("not found")) return new TrackerException(Kind.AUTH_INVALID, "The tracker rejected the credential");
        if (t.contains("not found") || t.contains("404") || t.contains("does not exist") || t.contains("issue does not exist")) return new TrackerException(Kind.NOT_FOUND, "The ticket was not found, or this account cannot see it");
        return new TrackerException(Kind.BAD_RESPONSE, "The tracker reported an error: " + truncate(text));
    }

    private static String truncate(String s) { return s.length() <= 200 ? s.strip() : s.substring(0, 200).strip() + "…"; }
}
