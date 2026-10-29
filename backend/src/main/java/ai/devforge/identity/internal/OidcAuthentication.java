package ai.devforge.identity.internal;

import ai.devforge.identity.CurrentUser;
import java.util.Collection;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated token into the signed-in {@link CurrentUser}. The provider is the authority on identity and on who is an administrator: the
 * administrator flag is set from the token on every sign-in, so removing someone from the admin group at the provider removes the right here too.
 */
class OidcAuthentication implements Converter<Jwt, AbstractAuthenticationToken> {
    private final OidcProperties props;
    private final UserService users;

    OidcAuthentication(OidcProperties props, UserService users) {
        this.props = props;
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String email = jwt.getClaimAsString(props.emailClaim());
        if (email == null || !email.matches("[^@\\s]{1,64}@[^@\\s]{1,255}")) throw invalid("The token carries no usable email address");
        if (Boolean.FALSE.equals(jwt.getClaim("email_verified"))) throw invalid("The provider has not verified this email address");
        String name = jwt.getClaimAsString(props.nameClaim());
        boolean admin = !props.adminValue().isBlank() && claimValues(jwt, props.adminClaim()).contains(props.adminValue());
        CurrentUser user = users.syncFromIdentityProvider(email, name == null || name.isBlank() ? email.substring(0, email.indexOf('@')) : name.strip(), admin);
        return new Authenticated(user, jwt);
    }

    private static Collection<String> claimValues(Jwt jwt, String claim) {
        Object v = jwt.getClaim(claim);
        if (v instanceof String s) return List.of(s);
        if (v instanceof Collection<?> c) return c.stream().map(String::valueOf).toList();
        return List.of();
    }

    private static OAuth2AuthenticationException invalid(String message) { return new OAuth2AuthenticationException(new OAuth2Error("invalid_token", message, null)); }

    /** The principal is the platform's own user, exactly as with the development sign-in, so nothing downstream knows how the person signed in. */
    static final class Authenticated extends AbstractAuthenticationToken {
        private final CurrentUser user;
        private final Jwt jwt;

        Authenticated(CurrentUser user, Jwt jwt) {
            super(List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority(user.admin() ? "ROLE_ADMIN" : "ROLE_NONE")));
            this.user = user;
            this.jwt = jwt;
            setAuthenticated(true);
        }

        @Override public Object getCredentials() { return jwt.getTokenValue(); }

        @Override public Object getPrincipal() { return user; }
    }
}
