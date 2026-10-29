package ai.devforge.integration.internal;

import ai.devforge.integration.internal.oauth.McpOAuth;
import ai.devforge.integration.internal.oauth.OAuthProperties;
import ai.devforge.integration.internal.oauth.OAuthSecret;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Providers that sign people in with an OAuth app an administrator registered (Bitbucket Cloud, GitHub): there is no automatic registration, so the
 * client id and secret come from the server's configuration. What differs between them (addresses, how the client authenticates, PKCE, default
 * scopes) is data here.
 */
@Component
class StaticOAuthProviders {
    /** @param key the configuration key under devforge.oauth.providers; @param basicClientAuth the client authenticates with HTTP Basic (otherwise in the request body) */
    record Spec(String type, String key, String authorizeUrl, String tokenUrl, boolean pkce, boolean basicClientAuth, String defaultScopes) {}

    private static final List<Spec> SPECS = List.of(
        new Spec("BITBUCKET_CLOUD", "bitbucket-cloud", "https://bitbucket.org/site/oauth2/authorize", "https://bitbucket.org/site/oauth2/access_token", false, true, ""),
        new Spec("GITHUB", "github", "https://github.com/login/oauth/authorize", "https://github.com/login/oauth/access_token", true, false, "repo"));

    private final OAuthProperties props;
    private final McpOAuth oauth;

    StaticOAuthProviders(OAuthProperties props, McpOAuth oauth) {
        this.props = props;
        this.oauth = oauth;
    }

    Optional<Spec> spec(String providerType) { return SPECS.stream().filter(s -> s.type().equals(providerType)).findFirst(); }

    Optional<OAuthProperties.Client> client(String providerType) { return spec(providerType).map(s -> props.providers().get(s.key())); }

    boolean configured(String providerType) { return client(providerType).map(OAuthProperties.Client::configured).orElse(false); }

    String authorizeUrl(Spec s) { return client(s.type()).map(OAuthProperties.Client::authorizeUrl).filter(u -> u != null && !u.isBlank()).orElse(s.authorizeUrl()); }

    String tokenUrl(Spec s) { return client(s.type()).map(OAuthProperties.Client::tokenUrl).filter(u -> u != null && !u.isBlank()).orElse(s.tokenUrl()); }

    String scopes(Spec s) { return client(s.type()).map(OAuthProperties.Client::scopes).filter(x -> !x.isBlank()).orElse(s.defaultScopes()); }

    McpOAuth.Tokens exchange(Spec s, String code, String redirectUri, String verifier) {
        var c = client(s.type()).orElseThrow();
        return oauth.exchangeWithClient(tokenUrl(s), c.clientId(), c.clientSecret(), s.basicClientAuth(), code, redirectUri, verifier);
    }

    McpOAuth.Tokens refresh(OAuthSecret secret) {
        Spec s = spec(secret.provider()).orElseThrow(() -> new McpOAuth.OAuthException("Unknown provider " + secret.provider()));
        var c = client(s.type()).filter(OAuthProperties.Client::configured).orElseThrow(() -> new McpOAuth.OAuthException("Single sign-on for " + s.type() + " is no longer configured on this server"));
        return oauth.refreshWithClient(secret.tokenEndpoint(), c.clientId(), c.clientSecret(), s.basicClientAuth(), secret.refreshToken());
    }

    boolean isStatic(String providerType) { return spec(providerType).isPresent(); }
}
