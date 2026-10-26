package ai.devforge.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the parts of the Bitbucket Cloud REST API DevForge uses (who am I, repository, pull requests). It accepts a Bearer token
 * or an app password (Basic). It proves our side of the protocol; it cannot prove how Bitbucket behaves.
 */
public final class FakeBitbucketServer implements AutoCloseable {
    public record Request(String method, String path, String authorization, String body) {}

    private static final Pattern PULLS = Pattern.compile("^/repositories/([^/]+)/([^/]+)/pullrequests$");
    private static final Pattern REPO = Pattern.compile("^/repositories/([^/]+)/([^/]+)$");
    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<String> openPulls = new CopyOnWriteArrayList<>();
    public volatile String acceptedBearer = "bb-token-valid";
    public volatile String acceptedBasic = "alice:app-password-1";
    public volatile String mainBranch = "main";
    public volatile int pullCreateStatus = 200;
    private volatile int next = 11;

    public FakeBitbucketServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    /** the API base the application is pointed at */
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public List<Request> requests() { return List.copyOf(requests); }

    public List<Request> pullCreations() { return requests.stream().filter(r -> r.method().equals("POST") && r.path().endsWith("/pullrequests")).toList(); }

    private boolean authorised(String auth) {
        if (auth == null) return false;
        if (auth.equals("Bearer " + acceptedBearer)) return true;
        return auth.startsWith("Basic ") && new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8).equals(acceptedBasic);
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = ex.getRequestURI().getPath();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        requests.add(new Request(ex.getRequestMethod(), path + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery()), String.valueOf(auth), body));
        if (!authorised(auth)) {
            reply(ex, 401, "{\"type\":\"error\",\"error\":{\"message\":\"Bad credentials\"}}");
            return;
        }
        Matcher pulls = PULLS.matcher(path), repo = REPO.matcher(path);
        String method = ex.getRequestMethod();
        if (path.equals("/user")) {
            reply(ex, 200, "{\"display_name\":\"Alice Dev\",\"username\":\"alice\"}");
        } else if (pulls.matches() && method.equals("GET")) {
            String query = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery(); // decoded
            List<String> hits = new ArrayList<>();
            for (String p : openPulls) if (query.contains("\"" + p.split("\\|", 2)[0] + "\"")) hits.add(p.split("\\|", 2)[1]);
            reply(ex, 200, "{\"values\":[" + String.join(",", hits) + "]}");
        } else if (pulls.matches() && method.equals("POST")) {
            if (pullCreateStatus != 200) {
                reply(ex, pullCreateStatus, "{\"type\":\"error\",\"error\":{\"message\":\"Pull request could not be created\",\"fields\":{\"destination\":[\"branch does not exist\"]}}}");
                return;
            }
            int n = next++;
            String source = body.replaceAll("(?s).*\"source\"\\s*:\\s*\\{\\s*\"branch\"\\s*:\\s*\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            String pr = "{\"id\":" + n + ",\"links\":{\"html\":{\"href\":\"https://bitbucket.test/acme/service/pull-requests/" + n + "\"}}}";
            openPulls.add(source + "|" + pr);
            reply(ex, 201, pr);
        } else if (repo.matches()) {
            if (repo.group(2).equals("missing")) reply(ex, 404, "{\"type\":\"error\",\"error\":{\"message\":\"Repository not found\"}}");
            else reply(ex, 200, "{\"mainbranch\":{\"name\":\"" + mainBranch + "\"}}");
        } else {
            reply(ex, 404, "{\"type\":\"error\",\"error\":{\"message\":\"Not found\"}}");
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
