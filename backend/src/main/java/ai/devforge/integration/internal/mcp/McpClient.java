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
    public static final Set<String> READ_ONLY_TOOLS = Set.of("getJiraIssue", "listJiraIssueComments", "searchJiraIssuesUsingJql", "getAccessibleAtlassianResources", "atlassianUserInfo");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY = 2_000_000;

    private final URI endpoint;
    private final String authorization;
    private final HttpClient http;
    private final Duration timeout;
    private String sessionId;
    /** What is being asked right now ("tools/call getJiraIssue"), so a failure can say which request the server refused. */
    private String context = "the handshake";
    private int nextId = 1;
    private boolean initialized;

    public McpClient(String endpoint, String secret, HttpClient http, Duration timeout) { this(endpoint, secret, http, timeout, false); }

    /** @param bearer the secret is an access token to be sent as a Bearer token whatever it contains (an OAuth token; {@code email:token} pairs are only for API tokens) */
    public McpClient(String endpoint, String secret, HttpClient http, Duration timeout, boolean bearer) {
        this.endpoint = URI.create(endpoint);
        this.authorization = bearer ? "Bearer " + secret.strip() : authorizationFor(secret);
        this.http = http;
        this.timeout = timeout;
    }

    /** {@code email:token} is sent as Basic (Atlassian API tokens); anything else as a Bearer token. */
    static String authorizationFor(String secret) {
        String s = secret.strip();
        return s.contains(":") ? "Basic " + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) : "Bearer " + s;
    }

    /** @param inputSchema the tool's JSON schema as the server describes it (parameter names differ between servers and versions) */
    public record Tool(String name, JsonNode inputSchema) {}

    private static final int MAX_TOOL_PAGES = 20;

    /** Every tool the server offers. The list is paginated (a server may send 50 at a time), so follow the cursor until the end. */
    public List<Tool> listToolSpecs() {
        initialize();
        List<Tool> tools = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            JsonNode result = request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
            result.path("tools").forEach(t -> { String name = t.path("name").asString(""); if (!name.isEmpty()) tools.add(new Tool(name, t.path("inputSchema"))); });
            cursor = result.path("nextCursor").asString("");
            if (cursor.isEmpty()) break;
        }
        return tools;
    }

    public List<String> listTools() { return listToolSpecs().stream().map(Tool::name).toList(); }

    /** Calls an allowlisted tool and returns the JSON found in its text output. */
    public JsonNode callTool(String name, Map<String, Object> arguments) {
        if (!READ_ONLY_TOOLS.contains(name)) throw new IllegalArgumentException("Tool is not on the read-only allowlist: " + name);
        initialize();
        JsonNode result = request("tools/call", Map.of("name", name, "arguments", arguments));
        StringBuilder text = new StringBuilder();
        result.path("content").forEach(c -> { if ("text".equals(c.path("type").asString(""))) text.append(c.path("text").asString("")).append('\n'); });
        if (result.path("isError").asBoolean(false)) throw toolError(name, text.toString());
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
        context = method.equals("tools/call") ? "tools/call " + params.get("name") : method;
        int id = nextId++;
        HttpResponse<String> res = send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params), true);
        JsonNode message = findResponse(res, id);
        if (message.has("error")) {
            String msg = message.path("error").path("message").asString("");
            int code = message.path("error").path("code").asInt(0);
            throw new TrackerException(code == 401 || code == 403 ? Kind.AUTH_INVALID : Kind.BAD_RESPONSE, "Tracker server reported an error during " + context + " (code " + code + "): " + excerpt(msg));
        }
        return message.path("result");
    }

    private HttpResponse<String> send(Map<String, Object> body, boolean expectBody) {
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Authorization", authorization)
            .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream");
        if (sessionId != null) b.header("Mcp-Session-Id", sessionId);
        try {
            HttpRequest request = b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res;
            try {
                res = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException first) {
                // a pooled connection the server already closed fails like this; everything this client sends is read-only, so one fresh attempt is safe
                res = http.send(request, HttpResponse.BodyHandlers.ofString());
            }
            int s = res.statusCode();
            if (s == 401 || s == 403) throw new TrackerException(Kind.AUTH_INVALID, "The tracker answered HTTP " + s + " during " + context + ": " + excerpt(res.body()));
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

    private static TrackerException toolError(String tool, String text) {
        String t = text.toLowerCase();
        if (t.contains("401") || t.contains("unauthorized") || t.contains("forbidden") || t.contains("403") && !t.contains("not found")) return new TrackerException(Kind.AUTH_INVALID, "The tracker refused " + tool + ": " + excerpt(text));
        if (t.contains("not found") || t.contains("404") || t.contains("does not exist") || t.contains("issue does not exist")) return new TrackerException(Kind.NOT_FOUND, "The ticket was not found, or this account cannot see it");
        return new TrackerException(Kind.BAD_RESPONSE, "The tracker reported an error: " + truncate(text));
    }

    /** What the server said, shortened and scrubbed (server error text describes the request, not our secrets; credentials are masked anyway). */
    private static String excerpt(String s) {
        String t = s == null ? "" : ai.devforge.platform.Redactor.redact(s.replaceAll("\\s+", " ").strip());
        return t.isEmpty() ? "(no details)" : t.length() <= 240 ? t : t.substring(0, 240) + "…";
    }

    private static String truncate(String s) { return s.length() <= 200 ? s.strip() : s.substring(0, 200).strip() + "…"; }
}
