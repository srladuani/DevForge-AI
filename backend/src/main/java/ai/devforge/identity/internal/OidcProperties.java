package ai.devforge.identity.internal;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in through an OpenID Connect provider (Keycloak, Okta, Entra ID, Google Workspace, …). The browser app signs people in with the
 * provider (authorization code + PKCE) and sends the access or ID token to this API as a bearer token; the API only validates it.
 *
 * @param issuerUri  the provider's issuer address; empty turns OIDC off. Its signing keys are fetched from the provider's metadata.
 * @param audience   if set, tokens must carry this audience (set it; many providers issue tokens for several apps)
 * @param clientId   the public (no secret) client the browser app uses; handed to the browser by /api/v1/auth/config
 * @param scopes     scopes the browser requests
 * @param emailClaim claim holding the person's email, which is their identity here
 * @param nameClaim  claim holding their display name
 * @param adminClaim claim (string or list) that decides administrators; with {@code adminValue}
 * @param adminValue the value in {@code adminClaim} that makes someone an administrator; empty = nobody is admin through the provider
 */
@ConfigurationProperties("devforge.security.oidc")
record OidcProperties(@DefaultValue("") String issuerUri, @Nullable String audience, @DefaultValue("") String clientId, @DefaultValue({"openid", "profile", "email"}) List<String> scopes,
                      @DefaultValue("email") String emailClaim, @DefaultValue("name") String nameClaim, @DefaultValue("groups") String adminClaim, @DefaultValue("") String adminValue) {
    boolean enabled() { return !issuerUri.isBlank(); }
}
