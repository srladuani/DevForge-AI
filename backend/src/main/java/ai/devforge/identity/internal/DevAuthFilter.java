package ai.devforge.identity.internal;

import ai.devforge.identity.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LOCAL DEVELOPMENT ONLY. Trusts the X-Devforge-User header (an email) as the login. Only registered when
 * devforge.security.dev-auth-enabled=true (the "local" profile); the application logs a warning when it is on.
 */
@Component
@ConditionalOnProperty("devforge.security.dev-auth-enabled")
class DevAuthFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(DevAuthFilter.class);
    private final UserService users;
    private final SecurityProperties props;

    DevAuthFilter(UserService users, SecurityProperties props) {
        this.users = users;
        this.props = props;
        log.warn("DEV AUTH IS ENABLED: any caller can act as any user via X-Devforge-User. Use only on a developer machine.");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String email = req.getHeader("X-Devforge-User");
        if (email != null && email.matches("[^@\\s]{1,64}@[^@\\s]{1,255}")) {
            boolean admin = props.devAdmins().stream().anyMatch(a -> a.equalsIgnoreCase(email));
            String name = req.getHeader("X-Devforge-Name") != null ? req.getHeader("X-Devforge-Name") : email.substring(0, email.indexOf('@'));
            CurrentUser u = users.getOrCreate(email, name, admin);
            var auth = new UsernamePasswordAuthenticationToken(u, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority(u.admin() ? "ROLE_ADMIN" : "ROLE_NONE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }
}
