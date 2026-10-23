package ai.devforge.identity.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Only exists when an issuer is configured. Without it the API accepts no bearer tokens at all. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OidcProperties.class)
class OidcConfig {

    @Bean
    @ConditionalOnExpression("!'${devforge.security.oidc.issuer-uri:}'.isBlank()")
    JwtDecoder oidcJwtDecoder(OidcProperties props) {
        // Building a decoder from an issuer contacts the provider, so it is built on first use and retried after a failure: the application can
        // start (and serve health checks) while the provider is briefly unreachable; sign-ins just fail with 401 until it is back.
        return new LazyJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(props.issuerUri()).build();
            decoder.setJwtValidator(validator(props));
            return decoder;
        });
    }

    static final class LazyJwtDecoder implements JwtDecoder {
        private final java.util.function.Supplier<JwtDecoder> factory;
        private volatile JwtDecoder delegate;

        LazyJwtDecoder(java.util.function.Supplier<JwtDecoder> factory) { this.factory = factory; }

        @Override
        public Jwt decode(String token) throws org.springframework.security.oauth2.jwt.JwtException {
            JwtDecoder d = delegate;
            if (d == null) {
                synchronized (this) {
                    d = delegate;
                    if (d == null) {
                        try {
                            d = factory.get();
                        } catch (RuntimeException e) {
                            throw new org.springframework.security.oauth2.jwt.JwtException("The identity provider could not be reached", e);
                        }
                        delegate = d;
                    }
                }
            }
            return d.decode(token);
        }
    }

    static OAuth2TokenValidator<Jwt> validator(OidcProperties props) {
        OAuth2TokenValidator<Jwt> audience = jwt -> {
            if (props.audience() == null || props.audience().isBlank() || (jwt.getAudience() != null && jwt.getAudience().contains(props.audience()))) return OAuth2TokenValidatorResult.success();
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The token is not for this application", null));
        };
        return new DelegatingOAuth2TokenValidator<>(List.of(JwtValidators.createDefaultWithIssuer(props.issuerUri()), new JwtTimestampValidator(Duration.ofSeconds(30)), audience));
    }
}
