package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.integration.GitHostException;
import ai.devforge.integration.GitHostException.Kind;
import ai.devforge.integration.GitHosts;
import ai.devforge.integration.HostCredential;
import ai.devforge.integration.ProviderInstanceView;
import ai.devforge.integration.Providers;
import ai.devforge.integration.PullRequestRef;
import ai.devforge.integration.RemoteRepo;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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
 * GitHub (and GitHub Enterprise) through the REST API with the user's own token. The token is sent only to the configured API host and, for
 * pushes, only to the matching git host: a repository whose remote points anywhere else is refused, so a crafted remote cannot collect it.
 */
@Component
class GitHubHost implements HostAdapter {
    static final String TYPE = "GITHUB";
    static final String DEFAULT_API = "https://api.github.com";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern HTTPS = Pattern.compile("^https://([^/@]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SCP = Pattern.compile("^(?:[\\w.-]+@)?([\\w.-]+):([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern SSH = Pattern.compile("^ssh://(?:[\\w.-]+@)?([\\w.-]+)(?::\\d+)?/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    private final Providers providers;
    private final OAuthTokens oauthTokens;
    private final boolean allowFileRemotes;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    GitHubHost(Providers providers, OAuthTokens oauthTokens, @Value("${devforge.github.allow-file-remotes:false}") boolean allowFileRemotes) {
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

    /** The one git host this instance's credentials may be sent to: api.github.com -> github.com; a GHE API address -> its own host. */
    @Override public String gitHostName() { return gitHost(); }

    String gitHost() {
        String host = URI.create(apiBase()).getHost();
        return host == null ? "github.com" : host.toLowerCase(Locale.ROOT).startsWith("api.") ? host.substring(4).toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT);
    }

    @Override
    public RemoteRepo resolve(String hostType, String remoteUrl) {
        String url = remoteUrl == null ? "" : remoteUrl.strip();
        Matcher m;
        if ((m = HTTPS.matcher(url)).matches() || (m = SSH.matcher(url)).matches() || (m = SCP.matcher(url)).matches()) {
            String host = m.group(1).toLowerCase(Locale.ROOT);
            if (!host.equals(gitHost())) throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "This repository's remote points to " + host + ", which is not the configured GitHub host (" + gitHost() + "). Your token is never sent anywhere else.");
            return remote(m.group(2), m.group(3), "https://" + host + "/" + m.group(2) + "/" + m.group(3) + ".git");
        }
        FileRemote file = allowFileRemotes ? FileRemote.parse(url) : null;
        if (file != null && (file.marker() == null || file.marker().equals("github"))) return remote(file.owner(), file.name(), url);
        throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "This repository has no GitHub remote that DevForge can use (found: " + (url.isEmpty() ? "none" : "an unrecognised address") + ").");
    }

    private RemoteRepo remote(String owner, String name, String pushUrl) {
        if (!NAME.matcher(owner).matches() || !NAME.matcher(name).matches()) throw new GitHostException(Kind.UNSUPPORTED_REMOTE, "The remote's owner or repository name is not valid.");
        return new RemoteRepo(TYPE, owner, name, pushUrl);
    }

    @Override
    public String gitAuthHeader(String hostType, HostCredential c) {
        return "Authorization: Basic " + Base64.getEncoder().encodeToString(("x-access-token:" + token(c)).getBytes(StandardCharsets.UTF_8));
    }

    /** The token as it is to be used: a personal access token as stored, or a sign-in's access token (renewed if it is about to expire). */
    private String token(HostCredential c) {
        String raw = c.secret().get().strip();
        return ai.devforge.integration.internal.oauth.OAuthSecret.looksLikeOAuth(raw) ? oauthTokens.accessToken(c) : raw;
    }

    @Override
    public String defaultBranch(String hostType, HostCredential c, RemoteRepo repo) {
        JsonNode r = call(c, "GET", "/repos/" + repo.owner() + "/" + repo.name(), null);
        String b = r.path("default_branch").asString("");
        if (b.isBlank()) throw new GitHostException(Kind.UNAVAILABLE, "GitHub did not say which branch is the default");
        return b;
    }

    @Override
    public PullRequestRef openPullRequest(String hostType, HostCredential c, RemoteRepo repo, String head, String base, String title, String body, boolean draft) {
        JsonNode r = call(c, "POST", "/repos/" + repo.owner() + "/" + repo.name() + "/pulls", Map.of("title", title, "head", head, "base", base, "body", body, "draft", draft));
        return ref(r);
    }

    @Override
    public Optional<PullRequestRef> findOpenPullRequest(String hostType, HostCredential c, RemoteRepo repo, String head) {
        JsonNode r = call(c, "GET", "/repos/" + repo.owner() + "/" + repo.name() + "/pulls?state=open&head=" + java.net.URLEncoder.encode(repo.owner() + ":" + head, StandardCharsets.UTF_8), null);
        return r.isArray() && !r.isEmpty() ? Optional.of(ref(r.get(0))) : Optional.empty();
    }

    private static PullRequestRef ref(JsonNode r) {
        int n = r.path("number").asInt(0);
        String url = r.path("html_url").asString("");
        if (n <= 0 || !url.startsWith("http")) throw new GitHostException(Kind.UNAVAILABLE, "GitHub answered in an unexpected shape");
        return new PullRequestRef(n, url);
    }

    private JsonNode call(HostCredential c, String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiBase() + path)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token(c))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "devforge-ai");
            if (body != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            else b.method(method, HttpRequest.BodyPublishers.noBody());
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s >= 200 && s < 300) return JSON.readTree(res.body());
            String message = message(res.body());
            if (s == 401 || s == 403) throw new GitHostException(Kind.AUTH_INVALID, "GitHub refused the token, or the account may not do this (" + message + ")");
            if (s == 404) throw new GitHostException(Kind.NOT_FOUND, "GitHub cannot find this repository, or the account cannot see it");
            if (s == 422) throw new GitHostException(Kind.REJECTED, message);
            throw new GitHostException(Kind.UNAVAILABLE, "GitHub answered HTTP " + s);
        } catch (IOException e) {
            throw new GitHostException(Kind.UNAVAILABLE, "Could not reach GitHub");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitHostException(Kind.UNAVAILABLE, "The request to GitHub was interrupted");
        } catch (GitHostException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new GitHostException(Kind.UNAVAILABLE, "GitHub answered in an unexpected shape");
        }
    }

    private static String message(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            StringBuilder sb = new StringBuilder(n.path("message").asString(""));
            n.path("errors").forEach(e -> { String m = e.path("message").asString(""); if (!m.isBlank()) sb.append(sb.isEmpty() ? "" : "; ").append(m); });
            String s = sb.toString().strip();
            return s.length() > 240 ? s.substring(0, 240) + "…" : s;
        } catch (RuntimeException e) {
            return "no details";
        }
    }
}
