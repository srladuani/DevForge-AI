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

    OAuthFlows(JdbcClient jdbc, McpOAuth oauth, OAuthProperties props, JiraTrackers jira, Connections connections, List<ConnectionVerifier> verifiers) {
        this.jdbc = jdbc;
        this.oauth = oauth;
        this.props = props;
        this.jira = jira;
        this.connections = connections;
        this.verifiers = verifiers;
    }

    /** @return the provider's sign-in page address for the browser to open */
    String start(UUID userId, String providerType, String label) {
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
            McpOAuth.Tokens t = oauth.exchange((String) flow[6], (String) flow[5], code, (String) flow[8], (String) flow[4], (String) flow[7]);
            String secret = new OAuthSecret(t.accessToken(), t.refreshToken(), t.expiresAtEpochSecond(), (String) flow[5], (String) flow[6], (String) flow[7]).toJson();
            ConnectionVerifier.Result result = verifiers.stream().filter(v -> v.supports(ConnectionKind.TRACKER, TYPE)).findFirst().map(v -> v.verify(secret))
                .orElse(new ConnectionVerifier.Result(ConnectionStatus.UNVERIFIED, "No verifier is available"));
            connections.saveOAuth(userId, ConnectionKind.TRACKER, TYPE, (String) flow[3], secret, result.status(), result.message());
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
