package ai.devforge.identity.internal;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Test access to the production token validators (issuer, time with a small skew, audience) without a network-backed decoder. */
public final class OidcValidators {
    private OidcValidators() {}

    public static OAuth2TokenValidator<Jwt> forTest(String issuer, String audience) {
        return OidcConfig.validator(new OidcProperties(issuer, audience, "", List.of("openid"), "email", "name", "groups", ""));
    }
}
