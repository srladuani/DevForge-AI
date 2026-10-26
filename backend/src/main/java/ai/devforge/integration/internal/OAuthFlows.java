package ai.devforge.integration.internal;

import ai.devforge.identity.ConnectionKind;
import ai.devforge.identity.ConnectionStatus;
import ai.devforge.identity.ConnectionVerifier;
import ai.devforge.identity.Connections;
import ai.devforge.integration.internal.oauth.McpOAuth;
import ai.devforge.integration.internal.oauth.OAuthProperties;
import ai.devforge.integration.internal.oauth.OAuthSecret;
import ai.devforge.platform.DomainException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Signing in to a tracker with the organisation's single sign-on instead of pasting a token. {@link #start} sends the person to the provider;
 * {@link #complete} runs when the browser comes back. The only thing the browser carries is a random, single-use, ten-minute {@code state} that is
 * bound to the person who started the flow, and tokens are stored encrypted in that person's own connection.
 */
@Service
class OAuthFlows {
    private static final Logger log = LoggerFactory.getLogger(OAuthFlows.class);
    private static final String TYPE = JiraTrackers.TYPE;

    private final JdbcClient jdbc;
    private final McpOAuth oauth;
    private final OAuthProperties props;
    private final JiraTrackers jira;
    private final Connections connections;
    private final List<ConnectionVerifier> verifiers;
    private final StaticOAuthProviders staticProviders;

    OAuthFlows(JdbcClient jdbc, McpOAuth oauth, OAuthProperties props, JiraTrackers jira, Connections connections, List<ConnectionVerifier> verifiers, StaticOAuthProviders staticProviders) {
        this.staticProviders = staticProviders;
        this.jdbc = jdbc;
        this.oauth = oauth;
        this.props = props;
        this.jira = jira;
        this.connections = connections;
        this.verifiers = verifiers;
    }

    /** @return the provider's sign-in page address for the browser to open */
    String start(UUID userId, String providerType, String label) {
        var staticSpec = staticProviders.spec(providerType);
        if (staticSpec.isPresent()) return startStatic(userId, staticSpec.get(), label);
        if (!TYPE.equals(providerType)) throw DomainException.unprocessable("OAUTH_NOT_SUPPORTED", "Signing in with single sign-on is not available for " + providerType);
        try {
            McpOAuth.Metadata meta = oauth.discover(jira.endpoint());
            String redirectUri = props.redirectUri();
            String clientId = oauth.register(meta, redirectUri);
            String state = McpOAuth.newState(), verifier = McpOAuth.newVerifier();
            jdbc.sql("delete from oauth_flow where expires_at < now()").update();
            jdbc.sql("insert into oauth_flow (state, user_id, kind, provider_type, label, code_verifier, client_id, token_endpoint, resource, redirect_uri, expires_at) values (?,?,?,?,?,?,?,?,?,?,?)")
                .params(state, userId, ConnectionKind.TRACKER.name(), providerType, label, verifier, clientId, meta.tokenEndpoint(), meta.resource(), redirectUri, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES))).update();
            return oauth.authorizationUrl(meta, clientId, redirectUri, state, verifier, oauth.scopeFor(meta));
        } catch (McpOAuth.OAuthException e) {
            throw DomainException.unprocessable("OAUTH_UNAVAILABLE", "Could not start the sign-in: " + e.getMessage());
        }
    }

    /** A provider with an administrator-registered OAuth app (Bitbucket Cloud, GitHub). */
    private String startStatic(UUID userId, StaticOAuthProviders.Spec spec, String label) {
        var client = staticProviders.client(spec.type()).filter(c -> c.configured()).orElseThrow(() -> DomainException.unprocessable("OAUTH_NOT_CONFIGURED",
            "Single sign-on for " + spec.type() + " is not set up on this server. An administrator must register an OAuth app with the provider and set its client id and secret (see the runbook)."));
        String redirectUri = props.redirectUri(), state = McpOAuth.newState(), verifier = spec.pkce() ? McpOAuth.newVerifier() : "";
        String authorize = staticProviders.authorizeUrl(spec), scope = staticProviders.scopes(spec);
        jdbc.sql("delete from oauth_flow where expires_at < now()").update();
        jdbc.sql("insert into oauth_flow (state, user_id, kind, provider_type, label, code_verifier, client_id, token_endpoint, resource, redirect_uri, expires_at) values (?,?,?,?,?,?,?,?,?,?,?)")
            .params(state, userId, ConnectionKind.GIT_HOST.name(), spec.type(), label, verifier, client.clientId(), staticProviders.tokenUrl(spec), "", redirectUri, Timestamp.from(Instant.now().plus(10, ChronoUnit.MINUTES))).update();
        StringBuilder url = new StringBuilder(authorize).append(authorize.contains("?") ? '&' : '?').append("response_type=code&client_id=").append(URLEncoder.encode(client.clientId(), StandardCharsets.UTF_8))
            .append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)).append("&state=").append(URLEncoder.encode(state, StandardCharsets.UTF_8));
        if (spec.pkce()) url.append("&code_challenge=").append(McpOAuth.challenge(verifier)).append("&code_challenge_method=S256");
        if (!scope.isBlank()) url.append("&scope=").append(URLEncoder.encode(scope, StandardCharsets.UTF_8));
        return url.toString();
    }

    /** Runs when the browser returns from the provider; always answers with a page of this application to go to, never an error page. */
    String complete(String state, String code, String error) {
        if (state == null || state.isBlank()) return back("error", "The sign-in did not say where it belongs. Start again.");
        var flow = jdbc.sql("delete from oauth_flow where state = ? returning user_id, kind, provider_type, label, code_verifier, client_id, token_endpoint, resource, redirect_uri, expires_at").param(state)
            .query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getTimestamp(10).toInstant()}).optional().orElse(null);
        if (flow == null || ((Instant) flow[9]).isBefore(Instant.now())) return back("error", "This sign-in link is no longer valid. Start again.");
        if (error != null && !error.isBlank()) return back("error", "The provider did not allow the sign-in (" + shorten(error) + ").");
        if (code == null || code.isBlank()) return back("error", "The provider returned no authorization code. Start again.");
        UUID userId = (UUID) flow[0];
        try {
            ConnectionKind kind = ConnectionKind.valueOf((String) flow[1]);
            String providerType = (String) flow[2];
            var staticSpec = staticProviders.spec(providerType);
            McpOAuth.Tokens t = staticSpec.isPresent() ? staticProviders.exchange(staticSpec.get(), code, (String) flow[8], (String) flow[4])
                : oauth.exchange((String) flow[6], (String) flow[5], code, (String) flow[8], (String) flow[4], (String) flow[7]);
            String secret = new OAuthSecret(t.accessToken(), t.refreshToken(), t.expiresAtEpochSecond(), (String) flow[5], (String) flow[6], (String) flow[7], providerType).toJson();
            ConnectionVerifier.Result result = verifiers.stream().filter(v -> v.supports(kind, providerType)).findFirst().map(v -> v.verify(secret))
                .orElse(new ConnectionVerifier.Result(ConnectionStatus.UNVERIFIED, "No verifier is available"));
            connections.saveOAuth(userId, kind, providerType, (String) flow[3], secret, result.status(), result.message());
            return back("success", null);
        } catch (McpOAuth.OAuthException e) {
            log.warn("Completing a sign-in failed: {}", e.getMessage());
            return back("error", "The sign-in could not be completed: " + e.getMessage());
        }
    }

    private String back(String outcome, String reason) {
        return props.base() + "/connections?oauth=" + outcome + (reason == null ? "" : "&reason=" + URLEncoder.encode(shorten(reason), StandardCharsets.UTF_8));
    }

    private static String shorten(String s) { return s.length() <= 220 ? s : s.substring(0, 220) + "…"; }
}
