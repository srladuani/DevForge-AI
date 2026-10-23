package ai.devforge.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.devforge.IntegrationTest;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.TestPropertySource;

/**
 * Sign-in through an OpenID Connect provider, with a locally generated key standing in for the provider's signing keys. The token validation
 * and the mapping to a platform user are the production code; what this does not prove is how a particular provider issues its tokens.
 */
@TestPropertySource(properties = {"devforge.security.dev-auth-enabled=false", "devforge.security.oidc.issuer-uri=https://idp.example.test/realms/acme", "devforge.security.oidc.audience=devforge",
    "devforge.security.oidc.client-id=devforge-web", "devforge.security.oidc.admin-value=devforge-admins"})
class OidcSignInTest extends IntegrationTest {
    private static final String ISSUER = "https://idp.example.test/realms/acme";
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER = generate();

    @TestConfiguration
    static class Provider {
        @Bean @Primary
        JwtDecoder testDecoder() throws Exception {
            NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
            // the production validators, with the same settings as the properties above
            d.setJwtValidator(ai.devforge.identity.internal.OidcValidators.forTest(ISSUER, "devforge"));
            return d;
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(KeyPair key, String issuer, String audience, Instant expires, java.util.function.Consumer<JWTClaimsSet.Builder> more) throws Exception {
        JWTClaimsSet.Builder c = new JWTClaimsSet.Builder().issuer(issuer).subject(UUID.randomUUID().toString()).audience(audience).issueTime(new Date()).expirationTime(Date.from(expires));
        more.accept(c);
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) key.getPrivate()));
        return jwt.serialize();
    }

    private static String valid(String email) throws Exception { return token(KEYS, ISSUER, "devforge", Instant.now().plusSeconds(600), c -> c.claim("email", email).claim("name", "Ada Lovelace")); }

    private int code(String path, String bearer) throws Exception {
        var req = get(path);
        if (bearer != null) req.header("Authorization", "Bearer " + bearer);
        return mvc.perform(req).andReturn().getResponse().getStatus();
    }

    @Test
    void aValidTokenSignsThePersonInAndCreatesTheirUserWithoutAnyAdminRights() throws Exception {
        String email = "Ada." + UUID.randomUUID().toString().substring(0, 6) + "@Example.Test";
        mvc.perform(get("/api/v1/workspaces").header("Authorization", "Bearer " + valid(email))).andExpect(status().isOk());
        assertThat(jdbc.sql("select display_name || '|' || admin from app_user where email = ?").param(email.toLowerCase()).query(String.class).single()).isEqualTo("Ada Lovelace|false");
        assertThat(code("/api/v1/admin/audit", valid(email))).isEqualTo(403);
    }

    @Test
    void theOldDevelopmentHeaderBuysNothingWhenOidcIsOnAndTokensInTheUrlAreIgnored() throws Exception {
        assertThat(mvc.perform(get("/api/v1/workspaces").header("X-Devforge-User", "someone@example.test")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(code("/api/v1/workspaces", null)).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/workspaces").param("access_token", valid("q@example.test"))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void tokensThatAreExpiredForeignWrongAudienceUnverifiedOrWithoutEmailAreRefused() throws Exception {
        assertThat(code("/api/v1/workspaces", token(KEYS, ISSUER, "devforge", Instant.now().minusSeconds(600), c -> c.claim("email", "a@example.test")))).as("expired").isEqualTo(401);
        assertThat(code("/api/v1/workspaces", token(OTHER, ISSUER, "devforge", Instant.now().plusSeconds(600), c -> c.claim("email", "a@example.test")))).as("signed by someone else").isEqualTo(401);
        assertThat(code("/api/v1/workspaces", token(KEYS, "https://evil.example", "devforge", Instant.now().plusSeconds(600), c -> c.claim("email", "a@example.test")))).as("wrong issuer").isEqualTo(401);
        assertThat(code("/api/v1/workspaces", token(KEYS, ISSUER, "another-app", Instant.now().plusSeconds(600), c -> c.claim("email", "a@example.test")))).as("wrong audience").isEqualTo(401);
        assertThat(code("/api/v1/workspaces", token(KEYS, ISSUER, "devforge", Instant.now().plusSeconds(600), c -> c.claim("email", "a@example.test").claim("email_verified", false)))).as("unverified email").isEqualTo(401);
        assertThat(code("/api/v1/workspaces", token(KEYS, ISSUER, "devforge", Instant.now().plusSeconds(600), c -> { }))).as("no email").isEqualTo(401);
        assertThat(code("/api/v1/workspaces", "not.a.jwt")).isEqualTo(401);
    }

    @Test
    void administratorRightsFollowTheProviderInBothDirections() throws Exception {
        String email = "boss." + UUID.randomUUID().toString().substring(0, 6) + "@example.test";
        String admin = token(KEYS, ISSUER, "devforge", Instant.now().plusSeconds(600), c -> c.claim("email", email).claim("groups", List.of("staff", "devforge-admins")));
        assertThat(code("/api/v1/admin/audit", admin)).isEqualTo(200);
        assertThat(code("/api/v1/admin/audit", valid(email))).as("removed from the group at the provider").isEqualTo(403);
        assertThat(code("/api/v1/admin/audit", admin)).isEqualTo(200);
    }

    @Test
    void theBrowserLearnsHowToSignInFromAPublicEndpointThatHoldsNoSecret() throws Exception {
        mvc.perform(get("/api/v1/auth/config")).andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("OIDC")).andExpect(jsonPath("$.issuer").value(ISSUER))
            .andExpect(jsonPath("$.clientId").value("devforge-web")).andExpect(jsonPath("$.scopes[0]").value("openid"));
    }
}
