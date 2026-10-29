package ai.devforge.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the parts of the GitHub REST API DevForge uses (who am I, repository, pull requests), with switches for the ways the real
 * service refuses. It proves our side of the protocol; it cannot prove how GitHub behaves.
 */
public final class FakeGitHubServer implements AutoCloseable {
    public record Request(String method, String path, String authorization, String body) {}

    private static final Pattern PULLS = Pattern.compile("^/repos/([^/]+)/([^/]+)/pulls$");
    private static final Pattern REPO = Pattern.compile("^/repos/([^/]+)/([^/]+)$");
    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> openPulls = new CopyOnWriteArrayList<>();
    public volatile String acceptedToken = "ghp_validtoken000000000000000000000000";
    public volatile String defaultBranch = "main";
    public volatile int pullCreateStatus = 201;
    public volatile String pullCreateMessage = "";
    private volatile int nextNumber = 7;

    public FakeGitHubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public List<Request> requests() { return List.copyOf(requests); }

    public List<Request> pullCreations() { return requests.stream().filter(r -> r.method().equals("POST") && r.path().endsWith("/pulls")).toList(); }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        requests.add(new Request(ex.getRequestMethod(), path + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery()), String.valueOf(auth), body));
        if (!("Bearer " + acceptedToken).equals(auth)) {
            reply(ex, 401, "{\"message\":\"Bad credentials\"}");
            return;
        }
        String method = ex.getRequestMethod();
        Matcher pulls = PULLS.matcher(path), repo = REPO.matcher(path);
        if (path.equals("/user")) {
            ex.getResponseHeaders().add("X-OAuth-Scopes", "repo");
            reply(ex, 200, "{\"login\":\"octo-dev\"}");
        } else if (pulls.matches() && method.equals("GET")) {
            String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery(); // decoded: head=acme:devforge/x
            List<String> hits = new ArrayList<>();
            for (String p : openPulls) if (query.contains("head=" + p.split("\\|", 2)[0])) hits.add(p.split("\\|", 2)[1]);
            reply(ex, 200, "[" + String.join(",", hits) + "]");
        } else if (pulls.matches() && method.equals("POST")) {
            if (pullCreateStatus != 201) {
                reply(ex, pullCreateStatus, "{\"message\":\"" + pullCreateMessage + "\"}");
                return;
            }
            int n = nextNumber++;
            String pr = "{\"number\":" + n + ",\"html_url\":\"https://github.test/acme/service/pull/" + n + "\"}";
            String head = body.replaceAll("(?s).*\"head\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            openPulls.add("acme:" + head + "|" + pr);
            reply(ex, 201, pr);
        } else if (repo.matches()) {
            if (repo.group(2).equals("missing")) reply(ex, 404, "{\"message\":\"Not Found\"}");
            else reply(ex, 200, "{\"default_branch\":\"" + defaultBranch + "\"}");
        } else {
            reply(ex, 404, "{\"message\":\"Not Found\"}");
        }
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
        ex.close();
    }

    @Override
    public void close() { server.stop(0); }
}
