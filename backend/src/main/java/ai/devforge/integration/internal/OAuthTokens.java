package ai.devforge.integration.internal;

import ai.devforge.integration.TrackerException;
import ai.devforge.integration.TrackerException.Kind;
import ai.devforge.integration.internal.oauth.McpOAuth;
import ai.devforge.integration.internal.oauth.OAuthSecret;
import ai.devforge.integration.TrackerCredential;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Gives out a usable access token for a connection made by signing in with a provider, renewing it (and storing the renewal) when it is about to expire. */
@Component
class OAuthTokens {
    private static final long RENEW_WHEN_LESS_THAN_SECONDS = 60;
    private final McpOAuth oauth;
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    private final StaticOAuthProviders staticProviders;

    OAuthTokens(McpOAuth oauth, StaticOAuthProviders staticProviders) {
        this.oauth = oauth;
        this.staticProviders = staticProviders;
    }

    String accessToken(TrackerCredential credential) { return accessToken(credential.connectionId(), credential.secret(), credential.persist()); }

    String accessToken(ai.devforge.integration.HostCredential credential) { return accessToken(credential.connectionId(), credential.secret(), credential.persist()); }

    private String accessToken(UUID connectionId, java.util.function.Supplier<String> secret, java.util.function.Consumer<String> persist) {
        // one renewal at a time per connection: providers rotate refresh tokens, so two parallel renewals would invalidate each other
        synchronized (locks.computeIfAbsent(connectionId, k -> new Object())) {
            OAuthSecret s = OAuthSecret.parse(secret.get());
            if (!s.expiresWithin(RENEW_WHEN_LESS_THAN_SECONDS)) return s.accessToken();
            if (s.refreshToken().isBlank()) throw expired("it cannot be renewed");
            try {
                McpOAuth.Tokens t = staticProviders.isStatic(s.provider()) ? staticProviders.refresh(s) : oauth.refresh(s.tokenEndpoint(), s.clientId(), s.refreshToken(), s.resource());
                OAuthSecret renewed = new OAuthSecret(t.accessToken(), t.refreshToken(), t.expiresAtEpochSecond(), s.clientId(), s.tokenEndpoint(), s.resource(), s.provider());
                persist.accept(renewed.toJson());
                return renewed.accessToken();
            } catch (McpOAuth.OAuthException e) {
                throw expired(e.getMessage());
            }
        }
    }

    private static TrackerException expired(String why) {
        return new TrackerException(Kind.AUTH_INVALID, "Your single sign-on has expired or was revoked (" + why + "). Sign in again under My connections.");
    }
}
