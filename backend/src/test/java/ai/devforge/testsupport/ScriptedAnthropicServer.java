package ai.devforge.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A local stand-in for the Anthropic API, so the adapter and the real-mode workflow can be tested end to end without a key or network.
 * It replays scripted replies to POST /v1/messages in order, answers GET /v1/models, and records what it was sent (including the key header).
 * It proves our side of the protocol; it cannot prove how the real service behaves.
 */
public final class ScriptedAnthropicServer implements AutoCloseable {
    public record Reply(int status, String body, long delayMillis) {}

    public record Recorded(String method, String path, String apiKey, String body) {}

    private final HttpServer server;
    private final Deque<Reply> messages = new ArrayDeque<>();
    private final List<Recorded> requests = new CopyOnWriteArrayList<>();
    private volatile Reply models = new Reply(200, "{\"data\":[{\"id\":\"claude-sonnet-5-5\"},{\"id\":\"claude-opus-5-5\"},{\"id\":\"claude-haiku-5-5\"}]}", 0);

    public ScriptedAnthropicServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public ScriptedAnthropicServer then(Reply r) { synchronized (messages) { messages.add(r); } return this; }

    public ScriptedAnthropicServer modelsReply(Reply r) { this.models = r; return this; }

    public List<Recorded> requests() { return List.copyOf(requests); }

    public List<Recorded> messageRequests() { return requests.stream().filter(r -> r.path().equals("/v1/messages")).toList(); }

    public int remaining() { synchronized (messages) { return messages.size(); } }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        requests.add(new Recorded(ex.getRequestMethod(), path, String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")), body));
        Reply reply;
        if (path.equals("/v1/models")) reply = models;
        else synchronized (messages) { reply = messages.poll(); }
        if (reply == null) reply = new Reply(500, error("api_error", "no scripted reply left"), 0);
        if (reply.delayMillis() > 0) {
            try {
                Thread.sleep(reply.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("content-type", "application/json");
        try {
            ex.sendResponseHeaders(reply.status(), out.length);
            ex.getResponseBody().write(out);
        } catch (IOException ignored) {
            // the client gave up (timeout test)
        } finally {
            ex.close();
        }
    }

    // ---- reply builders
    public static Reply toolUse(String id, String tool, String inputJson, int in, int out) {
        return new Reply(200, "{\"id\":\"msg_" + id + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + tool
            + "\",\"input\":" + inputJson + "}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out + "}}", 0);
    }

    public static Reply text(String text, int in, int out) {
        return new Reply(200, "{\"id\":\"msg_t\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"" + text.replace("\"", "\\\"")
            + "\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":" + in + ",\"output_tokens\":" + out + "}}", 0);
    }

    public static Reply failure(int status, String type, String message) { return new Reply(status, error(type, message), 0); }

    public static Reply slow(Reply r, long millis) { return new Reply(r.status(), r.body(), millis); }

    private static String error(String type, String message) { return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message.replace("\"", "'") + "\"}}"; }

    @Override
    public void close() { server.stop(0); }
}
