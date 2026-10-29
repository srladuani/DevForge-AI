package ai.devforge.identity.internal;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tells the browser app how to sign people in. Public (it is needed before there is a session) and contains nothing secret. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthConfigController {
    /** @param mode OIDC (sign in with the organisation's provider) or DEV (local development sign-in by email) or NONE (this server accepts no sign-in) */
    record AuthConfig(String mode, String issuer, String clientId, List<String> scopes) {}

    private final OidcProperties oidc;
    private final SecurityProperties security;

    AuthConfigController(OidcProperties oidc, SecurityProperties security) {
        this.oidc = oidc;
        this.security = security;
    }

    @GetMapping("/config")
    AuthConfig config() {
        if (oidc.enabled()) return new AuthConfig("OIDC", oidc.issuerUri(), oidc.clientId(), oidc.scopes());
        return new AuthConfig(security.devAuthEnabled() ? "DEV" : "NONE", "", "", List.of());
    }
}
