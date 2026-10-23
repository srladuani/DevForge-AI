package ai.devforge.spike;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Minimal fake of POST /v1/messages: first call -> tool_use, call carrying a tool_result -> final text. */
final class FakeAnthropic implements AutoCloseable {
    final HttpServer server;
    final List<String> apiKeys = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();

    FakeAnthropic() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            apiKeys.add(String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")));
            bodies.add(body);
            String out = body.contains("tool_result")
                ? "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":40,\"output_tokens\":7}}"
                : "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m\",\"content\":[{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"repo_grep\",\"input\":{\"pattern\":\"foo\"}}],\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":21,\"output_tokens\":9}}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); }
}
