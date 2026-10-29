package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import ai.devforge.integration.internal.oauth.OAuthSecret;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies a Bitbucket credential with one read-only call (who am I). A repository or workspace access token has no account scope, so it cannot
 * answer that call; such a token is reported as accepted-but-unconfirmed (it is exercised when a change is published).
 */
@Component
class BitbucketConnectionVerifier implements ConnectionVerifier {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final BitbucketHost bitbucket;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final OAuthTokens oauthTokens;
    private final org.springframework.beans.factory.ObjectProvider<ai.devforge.identity.Connections> connections;

    BitbucketConnectionVerifier(BitbucketHost bitbucket, OAuthTokens oauthTokens, org.springframework.beans.factory.ObjectProvider<ai.devforge.identity.Connections> connections) {
        this.bitbucket = bitbucket;
        this.oauthTokens = oauthTokens;
        this.connections = connections;
    }

    @Override
    public Result verify(java.util.UUID connectionId, java.util.UUID ownerUserId, String secret) {
        if (!OAuthSecret.looksLikeOAuth(secret)) return verify(secret);
        try {
            String token = oauthTokens.accessToken(new ai.devforge.integration.HostCredential(connectionId, ownerUserId, () -> secret, s -> connections.getObject().replaceSecret(connectionId, ownerUserId, s)));
            return check("Bearer " + token, false);
        } catch (ai.devforge.integration.TrackerException e) {
            return new Result(ConnectionStatus.INVALID, e.getMessage());
        }
    }

    @Override
    public boolean supports(ConnectionKind kind, String providerType) { return kind == ConnectionKind.GIT_HOST && BitbucketHost.TYPE.equals(providerType); }

    @Override
    public Result verify(String secret) {
        String raw = secret.strip();
        boolean appPassword = !OAuthSecret.looksLikeOAuth(raw) && raw.contains(":");
        String token = OAuthSecret.looksLikeOAuth(raw) ? OAuthSecret.parse(raw).accessToken() : raw;
        boolean bareAccessToken = !appPassword && !OAuthSecret.looksLikeOAuth(raw);
        return check(appPassword ? "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)) : "Bearer " + token, bareAccessToken);
    }

    private Result check(String authorization, boolean bareAccessToken) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(bitbucket.apiBase() + "/user")).timeout(Duration.ofSeconds(20)).header("Authorization", authorization).header("Accept", "application/json").header("User-Agent", "devforge-ai").GET().build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int s = res.statusCode();
            if (s == 200) {
                var n = JSON.readTree(res.body());
                String who = n.path("display_name").asString(n.path("username").asString(""));
                return new Result(ConnectionStatus.VALID, "Signed in to Bitbucket as " + who);
            }
            if (s == 401 && bareAccessToken) {
                // Bitbucket answers 401 on this endpoint for a repository or workspace access token ("not supported for this endpoint") exactly as it does for a wrong one,
                // so it cannot be told apart here. The token is used on the repository itself when a pull request is created, and a wrong one fails there, clearly.
                return new Result(ConnectionStatus.VALID, "Bitbucket would not confirm this token on the account endpoint, which is normal for a repository or workspace access token (it cannot say whose it is). It is checked on the repository when you create a pull request, so make sure it has Repositories: Write and Pull requests: Write.");
            }
            if (s == 401) return new Result(ConnectionStatus.INVALID, "Bitbucket rejected this credential");
            if (s == 403) return new Result(ConnectionStatus.VALID, "Bitbucket accepted this credential but it has no account scope, so it cannot say who it belongs to. It will be used when you publish; make sure it can write to the repository and pull requests.");
            if (s == 429) return new Result(ConnectionStatus.QUOTA, "Bitbucket is rate limiting this credential");
            if (s >= 500) return new Result(ConnectionStatus.UNVERIFIED, "Bitbucket is temporarily unavailable; try again");
            return new Result(ConnectionStatus.INVALID, "Bitbucket answered HTTP " + s);
        } catch (IOException | RuntimeException e) {
            return new Result(ConnectionStatus.UNVERIFIED, "Could not reach Bitbucket; check the network and try again");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(ConnectionStatus.UNVERIFIED, "Verification was interrupted");
        }
    }
}
