package ai.devforge.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for an OAuth 2.1 login service with dynamic client registration and PKCE (the way Atlassian's MCP server signs people in).
 * It checks the PKCE verifier against the challenge the test read from the authorization address, and rotates refresh tokens.
 * It proves our side of the protocol; it cannot prove how a particular provider behaves.
 */
public final class FakeOAuthServer implements AutoCloseable {
    private final HttpServer server;
    private final List<String> registrations = new CopyOnWriteArrayList<>();
    private final AtomicInteger refreshes = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger(1);
    public volatile String expectedChallenge;
    public volatile int expiresIn = 3600;
    public volatile boolean refreshFails;
    public volatile String currentAccess = "at-1";
    public volatile String currentRefresh = "rt-1";
    public volatile String authorizeHost; // for the "metadata points somewhere else" test

    public FakeOAuthServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    public List<String> registrations() { return List.copyOf(registrations); }

    public int refreshes() { return refreshes.get(); }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        switch (path) {
            case "/.well-known/oauth-authorization-server" -> reply(ex, 200, "{\"issuer\":\"" + url() + "\",\"authorization_endpoint\":\"" + (authorizeHost != null ? authorizeHost : url()) + "/authorize\",\"token_endpoint\":\"" + url()
                + "/token\",\"registration_endpoint\":\"" + url() + "/register\",\"code_challenge_methods_supported\":[\"S256\"],\"scopes_supported\":[\"offline_access\",\"read:jira:agent-interface\",\"search:jira:agent-interface\",\"write:jira:agent-interface\"]}");
            case "/register" -> {
                registrations.add(body);
                reply(ex, 201, "{\"client_id\":\"client-1\"}");
            }
            case "/token" -> token(ex, form(body));
            default -> reply(ex, 404, "{}");
        }
    }

    private void token(HttpExchange ex, Map<String, String> f) throws IOException {
        String grant = f.getOrDefault("grant_type", "");
        if (grant.equals("authorization_code")) {
            if (!"code-1".equals(f.get("code")) || expectedChallenge == null || !expectedChallenge.equals(challenge(f.getOrDefault("code_verifier", "")))) {
                reply(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"code or verifier does not match\"}");
                return;
            }
            reply(ex, 200, tokens(currentAccess, currentRefresh));
        } else if (grant.equals("refresh_token")) {
            if (refreshFails || !currentRefresh.equals(f.get("refresh_token"))) {
                reply(ex, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"refresh token revoked\"}");
                return;
            }
            refreshes.incrementAndGet();
            int n = sequence.incrementAndGet();
            currentAccess = "at-" + n;
            currentRefresh = "rt-" + n;
            expiresIn = 3600;
            reply(ex, 200, tokens(currentAccess, currentRefresh));
        } else {
            reply(ex, 400, "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private String tokens(String access, String refresh) {
        return "{\"access_token\":\"" + access + "\",\"refresh_token\":\"" + refresh + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}";
    }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) m.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
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
