package ai.devforge.integration.internal.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param redirectBase           the address people reach this application at (the browser comes back to {@code <base>/api/v1/oauth/callback}); must be reachable
 *                               by the browser and allowed by the provider
 * @param allowInsecureLocalhost accept plain-http provider endpoints on loopback addresses (tests only)
 */
@ConfigurationProperties("devforge.oauth")
public record OAuthProperties(@DefaultValue("http://localhost:8080") String redirectBase, @DefaultValue("false") boolean allowInsecureLocalhost) {
    public String redirectUri() { return (redirectBase.endsWith("/") ? redirectBase.substring(0, redirectBase.length() - 1) : redirectBase) + "/api/v1/oauth/callback"; }

    public String base() { return redirectBase.endsWith("/") ? redirectBase.substring(0, redirectBase.length() - 1) : redirectBase; }
}
