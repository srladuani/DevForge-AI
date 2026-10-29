package ai.devforge.integration.internal.oauth;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The client side of OAuth 2.1 for an MCP server: find the provider's login (RFC 9728 and 8414 discovery), register this application with it
 * automatically (RFC 7591), and use authorization code + PKCE, then refresh tokens. Every address learned from a server's metadata is checked
 * before it is used: https only, and the same organisation as the MCP server, so a hostile or compromised metadata document cannot send people or
 * tokens somewhere else.
 */
@Component
public class McpOAuth {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_BODY = 1_000_000;

    public static final class OAuthException extends RuntimeException {
        public OAuthException(String message) { super(message); }
    }

    public record Metadata(String resource, String authorizationEndpoint, String tokenEndpoint, String registrationEndpoint, Set<String> scopes) {}

    public record Tokens(String accessToken, String refreshToken, long expiresAtEpochSecond) {}

    private final OAuthProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    McpOAuth(OAuthProperties props) { this.props = props; }

    public Metadata discover(String mcpUrl) {
        URI mcp = URI.create(mcpUrl);
        String origin = mcp.getScheme() + "://" + mcp.getAuthority();
        String path = mcp.getPath() == null ? "" : mcp.getPath();
        JsonNode protectedResource = getJson(origin + "/.well-known/oauth-protected-resource" + path, mcp.getHost());
        JsonNode servers = protectedResource.path("authorization_servers");
        String issuer = servers.isArray() && !servers.isEmpty() ? servers.get(0).asString("") : "";
        if (issuer.isBlank()) throw new OAuthException("The server does not say which login service it uses");
        checkAddress(issuer, mcp.getHost());
        URI as = URI.create(issuer);
        String asOrigin = as.getScheme() + "://" + as.getAuthority();
        String asPath = as.getPath() == null || as.getPath().equals("/") ? "" : as.getPath();
        JsonNode meta;
        try {
            meta = getJson(asOrigin + "/.well-known/oauth-authorization-server" + asPath, mcp.getHost());
        } catch (OAuthException first) {
            meta = getJson(asOrigin + asPath + "/.well-known/openid-configuration", mcp.getHost());
        }
        JsonNode methods = meta.path("code_challenge_methods_supported");
        if (methods.isArray() && !methods.isEmpty() && java.util.stream.StreamSupport.stream(methods.spliterator(), false).noneMatch(m -> "S256".equals(m.asString("")))) {
            throw new OAuthException("The login service does not support PKCE (S256)");
        }
        String authorize = required(meta, "authorization_endpoint"), token = required(meta, "token_endpoint"), register = required(meta, "registration_endpoint");
        for (String url : List.of(authorize, token, register)) checkAddress(url, mcp.getHost());
        Set<String> scopes = new HashSet<>();
        meta.path("scopes_supported").forEach(s -> scopes.add(s.asString("")));
        String resource = protectedResource.path("resource").asString(origin + path);
        return new Metadata(resource, authorize, token, register, scopes);
    }

    /** Registers this application with the login service and returns the client id it was given (a public client: no secret). */
    public String register(Metadata m, String redirectUri) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_name", "DevForge AI");
        body.put("redirect_uris", List.of(redirectUri));
        body.put("grant_types", List.of("authorization_code", "refresh_token"));
        body.put("response_types", List.of("code"));
        body.put("token_endpoint_auth_method", "none");
        JsonNode r = postJson(m.registrationEndpoint(), body);
        String id = r.path("client_id").asString("");
        if (id.isBlank()) throw new OAuthException("The login service did not register this application");
        return id;
    }

    /** Read-only scopes where the provider lists them, plus offline access so the sign-in can be renewed; nothing is requested if none are listed. */
    public String scopeFor(Metadata m) {
        List<String> wanted = m.scopes().stream().filter(s -> s.equals("offline_access") || s.matches("(read|search):jira.*")).sorted().collect(Collectors.toList());
        return String.join(" ", wanted);
    }

    public static String newVerifier() { return randomUrlToken(48); }

    public static String newState() { return randomUrlToken(32); }

    public static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String authorizationUrl(Metadata m, String clientId, String redirectUri, String state, String verifier, String scope) {
        StringBuilder sb = new StringBuilder(m.authorizationEndpoint()).append(m.authorizationEndpoint().contains("?") ? '&' : '?');
        sb.append("response_type=code&client_id=").append(enc(clientId)).append("&redirect_uri=").append(enc(redirectUri)).append("&code_challenge=").append(challenge(verifier))
            .append("&code_challenge_method=S256&state=").append(enc(state)).append("&resource=").append(enc(m.resource()));
        if (!scope.isBlank()) sb.append("&scope=").append(enc(scope));
        return sb.toString();
    }

    public Tokens exchange(String tokenEndpoint, String clientId, String code, String redirectUri, String verifier, String resource) {
        checkTokenEndpoint(tokenEndpoint);
        return tokens(postForm(tokenEndpoint, Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri, "client_id", clientId, "code_verifier", verifier, "resource", resource)), "");
    }

    public Tokens refresh(String tokenEndpoint, String clientId, String refreshToken, String resource) {
        checkTokenEndpoint(tokenEndpoint);
        return tokens(postForm(tokenEndpoint, Map.of("grant_type", "refresh_token", "refresh_token", refreshToken, "client_id", clientId, "resource", resource)), refreshToken);
    }

    private static Tokens tokens(JsonNode r, String previousRefresh) {
        String access = r.path("access_token").asString("");
        if (access.isBlank()) throw new OAuthException("The login service did not return an access token");
        String refresh = r.path("refresh_token").asString(previousRefresh);
        // a provider that sends neither a lifetime nor a refresh token issues tokens that do not expire (GitHub OAuth apps by default)
        long expiresAt = r.has("expires_in") ? java.time.Instant.now().plusSeconds(Math.max(r.path("expires_in").asLong(3600), 1)).getEpochSecond() : refresh.isBlank() ? OAuthSecret.NEVER : java.time.Instant.now().plusSeconds(3600).getEpochSecond();
        return new Tokens(access, refresh, expiresAt);
    }

    /** Code exchange for a provider with a pre-registered client: the client authenticates with its secret (HTTP Basic, or in the body). */
    public Tokens exchangeWithClient(String tokenEndpoint, String clientId, String clientSecret, boolean basicAuth, String code, String redirectUri, @org.jspecify.annotations.Nullable String verifier) {
        checkTokenEndpoint(tokenEndpoint);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        if (verifier != null && !verifier.isBlank()) form.put("code_verifier", verifier);
        if (!basicAuth) { form.put("client_id", clientId); form.put("client_secret", clientSecret); }
        return tokens(postForm(tokenEndpoint, form, basicAuth ? basic(clientId, clientSecret) : null), "");
    }

    public Tokens refreshWithClient(String tokenEndpoint, String clientId, String clientSecret, boolean basicAuth, String refreshToken) {
        checkTokenEndpoint(tokenEndpoint);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        if (!basicAuth) { form.put("client_id", clientId); form.put("client_secret", clientSecret); }
        return tokens(postForm(tokenEndpoint, form, basicAuth ? basic(clientId, clientSecret) : null), refreshToken);
    }

    private static String basic(String id, String secret) { return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8)); }

    // ---- addresses and HTTP

    private void checkTokenEndpoint(String url) {
        // a stored endpoint was checked when it was learned; check again that it is still an acceptable address before a refresh token is sent to it
        String host = URI.create(url).getHost();
        checkAddress(url, host);
    }

    /** https (or loopback http when allowed for tests), and the same organisation (last two host labels) as the reference host. */
    void checkAddress(String url, String referenceHost) {
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new OAuthException("The server gave an address that is not valid");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1");
        boolean secure = "https".equals(u.getScheme()) || (props.allowInsecureLocalhost() && "http".equals(u.getScheme()) && loopback);
        if (!secure || host.isEmpty() || u.getUserInfo() != null) throw new OAuthException("The server gave an address that is not allowed (" + (host.isEmpty() ? "none" : host) + ")");
        if (!sameOrganisation(host, referenceHost.toLowerCase())) throw new OAuthException("The server pointed to a different organisation (" + host + "), which is not trusted");
    }

    static boolean sameOrganisation(String a, String b) {
        if (a.equals(b)) return true;
        String[] x = a.split("\\."), y = b.split("\\.");
        return x.length >= 2 && y.length >= 2 && x[x.length - 1].equals(y[y.length - 1]) && x[x.length - 2].equals(y[y.length - 2]) && !a.matches("[0-9.]+");
    }

    private JsonNode getJson(String url, String referenceHost) {
        checkAddress(url, referenceHost);
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build());
    }

    private JsonNode postJson(String url, Object body) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json").header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build());
    }

    private JsonNode postForm(String url, Map<String, String> form) { return postForm(url, form, null); }

    private JsonNode postForm(String url, Map<String, String> form, @org.jspecify.annotations.Nullable String authorization) {
        String body = form.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue())).collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json");
        if (authorization != null) b.header("Authorization", authorization);
        return send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.body() != null && res.body().length() > MAX_BODY) throw new OAuthException("The login service answered with too much data");
            if (res.statusCode() / 100 != 2) {
                String detail = "";
                try {
                    JsonNode e = JSON.readTree(res.body());
                    detail = e.path("error").asString("") + (e.path("error_description").isString() ? ": " + e.path("error_description").asString() : "");
                } catch (RuntimeException ignored) {
                    // no structured error
                }
                throw new OAuthException("The login service answered HTTP " + res.statusCode() + (detail.isBlank() ? "" : " (" + (detail.length() > 200 ? detail.substring(0, 200) : detail) + ")"));
            }
            return JSON.readTree(res.body());
        } catch (IOException e) {
            throw new OAuthException("The login service could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OAuthException("The request was interrupted");
        }
    }

    private static String required(JsonNode meta, String field) {
        String v = meta.path(field).asString("");
        if (v.isBlank()) throw new OAuthException("The login service's metadata has no " + field);
        return v;
    }

    private static String randomUrlToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
