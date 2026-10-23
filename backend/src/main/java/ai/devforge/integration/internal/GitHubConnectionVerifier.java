package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Verifies a GitHub token with one read-only call (who am I). Nothing is created or changed on GitHub. */
@Component
class GitHubConnectionVerifier implements ConnectionVerifier {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final GitHubHost github;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    GitHubConnectionVerifier(GitHubHost github) { this.github = github; }

    @Override
    public boolean supports(ConnectionKind kind, String providerType) { return kind == ConnectionKind.GIT_HOST && GitHubHost.TYPE.equals(providerType); }

    @Override
    public Result verify(String secret) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(github.apiBase() + "/user")).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + secret.strip())
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "devforge-ai").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 200) {
                String login = JSON.readTree(res.body()).path("login").asString("");
                String scopes = res.headers().firstValue("X-OAuth-Scopes").orElse("");
                String note = scopes.isBlank() ? "" : scopes.contains("repo") ? "" : " (this classic token has no `repo` scope, so it cannot push or open pull requests)";
                return new Result(scopes.isBlank() || scopes.contains("repo") ? ConnectionStatus.VALID : ConnectionStatus.INVALID, "Signed in to GitHub as " + login + note);
            }
            if (s == 401) return new Result(ConnectionStatus.INVALID, "GitHub rejected this token");
            if (s == 403 || s == 429) return new Result(ConnectionStatus.QUOTA, "GitHub is rate limiting this token or refused it");
            if (s >= 500) return new Result(ConnectionStatus.UNVERIFIED, "GitHub is temporarily unavailable; try again");
            return new Result(ConnectionStatus.INVALID, "GitHub answered HTTP " + s);
        } catch (IOException | RuntimeException e) {
            return new Result(ConnectionStatus.UNVERIFIED, "Could not reach GitHub; check the network and try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(ConnectionStatus.UNVERIFIED, "Verification was interrupted");
        }
    }
}
