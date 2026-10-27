package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.GitHostException;
import ai.devforge.integration.GitHostException.Kind;
import ai.devforge.integration.HostCredential;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import ai.devforge.integration.PullRequestRef;
import ai.devforge.integration.RemoteRepo;
import ai.devforge.integration.internal.oauth.OAuthSecret;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bitbucket Cloud through its REST API with the user's own credential: a sign-in (OAuth), a repository or workspace access token, or an app
 * password ({@code username:app-password}). The credential is sent only to the configured API host and, for pushes, only to the matching git host.
 */
@Component
class BitbucketHost implements HostAdapter {
    static final String TYPE = "BITBUCKET_CLOUD";
    static final String DEFAULT_API = "https://api.bitbucket.org/2.0";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern HTTPS = Pattern.compile("^https://(?:[^/@]+@)?([^/@]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SCP = Pattern.compile("^(?:[\\w.-]+@)?([\\w.-]+):([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SSH = Pattern.compile("^ssh://(?:[\\w.-]+@)?([\\w.-]+)(?::\\d+)?/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final Providers providers;
    private final OAuthTokens oauthTokens;
    private final boolean allowFileRemotes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    BitbucketHost(Providers providers, OAuthTokens oauthTokens, @Value("${devforge.bitbucket.allow-file-remotes:false}") boolean allowFileRemotes) {
        this.providers = providers;
        this.oauthTokens = oauthTokens;
        this.allowFileRemotes = allowFileRemotes;
    }

    @Override public boolean supports(String hostType) { return TYPE.equals(hostType); }

    @Override public RemoteRepo detect(String remoteUrl) { return resolve(TYPE, remoteUrl); }

    String apiBase() {
        String url = providers.findEnabled(ConnectionKind.GIT_HOST, TYPE).map(ProviderInstanceView::baseUrl).orElse(null);
        String base = url == null || url.isBlank() ? DEFAULT_API : url.trim();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The one git host this instance's credentials may be sent to: api.bitbucket.org -> bitbucket.org. */
    @Override public String gitHostName() { return gitHost(); }

    String gitHost() {
        String host = URI.create(apiBase()).getHost();
        String h = host == null ? "bitbucket.org" : host.toLowerCase(Locale.ROOT);
        return h.startsWith("api.") ? h.substring(4) : h;
    }

    @Override
    public RemoteRepo resolve(String hostType, String remoteUrl) {
        String url = remoteUrl == null ? "" : remoteUrl.strip();
        Matcher m;
        if ((m = HTTPS.matcher(url)).matches() || (m = SSH.matcher(url)).matches() || (m = SCP.matcher(url)).matches()) {
            String host = m.group(1).toLowerCase(Locale.ROOT);
            if (!host.equals(gitHost())) throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "This repository's remote points to " + host + ", which is not the configured Bitbucket host (" + gitHost() + "). Your credential is never sent anywhere else.");
            return remote(m.group(2), m.group(3), "https://" + host + "/" + m.group(2) + "/" + m.group(3) + ".git");
        }
        FileRemote file = allowFileRemotes ? FileRemote.parse(url) : null;
        if (file != null && "bitbucket".equals(file.marker())) return remote(file.owner(), file.name(), url);
        throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "This repository has no Bitbucket remote that DevForge can use (found: " + (url.isEmpty() ? "none" : "an unrecognised address") + ").");
    }

    private RemoteRepo remote(String workspace, String repo, String pushUrl) {
        if (!NAME.matcher(workspace).matches() || !NAME.matcher(repo).matches()) throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "The remote's workspace or repository name is not valid.");
        return new RemoteRepo(TYPE, workspace, repo, pushUrl);
    }

    /** The secret as it is to be used: a sign-in's access token (renewed if needed), an app password, or an access token. */
    private record Secret(String value, boolean appPassword) {}

    private Secret secretOf(HostCredential c) {
        String raw = c.secret().get().strip();
        if (OAuthSecret.looksLikeOAuth(raw)) return new Secret(oauthTokens.accessToken(c), false);
        return new Secret(raw, raw.contains(":"));
    }

    @Override
    public String gitAuthHeader(String hostType, HostCredential c) {
        Secret s = secretOf(c);
        String userAndPassword = s.appPassword() ? s.value() : "x-token-auth:" + s.value();
        return "Authorization: Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    private String apiAuthorization(HostCredential c) {
        Secret s = secretOf(c);
        return s.appPassword() ? "Basic " + Base64.getEncoder().encodeToString(s.value().getBytes(StandardCharsets.UTF_8)) : "Bearer " + s.value();
    }

    @Override
    public String defaultBranch(String hostType, HostCredential c, RemoteRepo repo) {
        JsonNode r = call(c, "GET", "/repositories/" + repo.owner() + "/" + repo.name(), null);
        String b = r.path("mainbranch").path("name").asString("");
        if (b.isBlank()) throw new GitHostException(Kind.UNAVAILABLE, "Bitbucket did not say which branch is the main one; set the target branch in the project's publishing settings");
        return b;
    }

    @Override
    public PullRequestRef openPullRequest(String hostType, HostCredential c, RemoteRepo repo, String head, String base, String title, String body, boolean draft) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("title", title);
        req.put("description", body);
        req.put("source", Map.of("branch", Map.of("name", head)));
        req.put("destination", Map.of("branch", Map.of("name", base)));
        req.put("close_source_branch", false);
        req.put("draft", draft);
        return ref(call(c, "POST", "/repositories/" + repo.owner() + "/" + repo.name() + "/pullrequests", req));
    }

    @Override
    public Optional<PullRequestRef> findOpenPullRequest(String hostType, HostCredential c, RemoteRepo repo, String head) {
        String q = URLEncoder.encode("source.branch.name=\"" + head + "\" AND state=\"OPEN\"", StandardCharsets.UTF_8);
        JsonNode r = call(c, "GET", "/repositories/" + repo.owner() + "/" + repo.name() + "/pullrequests?q=" + q, null);
        JsonNode values = r.path("values");
        return values.isArray() && !values.isEmpty() ? Optional.of(ref(values.get(0))) : Optional.empty();
    }

    private static PullRequestRef ref(JsonNode r) {
        int n = r.path("id").asInt(0);
        String url = r.path("links").path("html").path("href").asString("");
        if (n <= 0 || !url.startsWith("http")) throw new GitHostException(Kind.UNAVAILABLE, "Bitbucket answered in an unexpected shape");
        return new PullRequestRef(n, url);
    }

    private JsonNode call(HostCredential c, String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase() + path)).timeout(Duration.ofSeconds(30)).header("Authorization", apiAuthorization(c)).header("Accept", "application/json").header("User-Agent", "devforge-ai");
            if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            else b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> res = HostHttp.send(http, b.build());
            int s = res.statusCode();
            if (s >= 200 && s < 300) return JSON.readTree(res.body());
            String message = message(res.body());
            if (s == 401 || s == 403) throw new GitHostException(Kind.AUTH_INVALID, "Bitbucket refused the credential, or it may not do this (" + message + ")");
            if (s == 404) throw new GitHostException(Kind.NOT_FOUND, "Bitbucket cannot find this repository, or the credential cannot see it");
            if (s == 400 || s == 409 || s == 422) throw new GitHostException(Kind.REJECTED, message);
            throw new GitHostException(Kind.UNAVAILABLE, "Bitbucket answered HTTP " + s);
        } catch (IOException e) {
            throw new GitHostException(Kind.UNAVAILABLE, "Could not reach Bitbucket (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHostException(Kind.UNAVAILABLE, "The request to Bitbucket was interrupted");
        } catch (GitHostException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GitHostException(Kind.UNAVAILABLE, "Bitbucket answered in an unexpected shape");
        }
    }

    private static String message(String body) {
        try {
            JsonNode n = JSON.readTree(body).path("error");
            String m = n.path("message").asString("");
            JsonNode fields = n.path("fields");
            if (fields.isObject()) m = m + " " + fields.toString();
            String s = m.strip();
            return s.length() > 240 ? s.substring(0, 240) + "…" : (s.isEmpty() ? "no details" : s);
        } catch (RuntimeException e) {
            return "no details";
        }
    }
}
