package ai.devforge.identity.internal;

import ai.devforge.identity.CurrentUser;
import ai.devforge.identity.CurrentUsers;
import ai.devforge.platform.DomainException;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Deny by default. Only health/info and the OpenAPI document are public. The API is stateless (no cookies), so CSRF protection
 * does not apply; the production authenticator (OIDC) is added in a later milestone. Without dev auth every API call is a 401.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectProvider<DevAuthFilter> devAuth) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.contentTypeOptions(Customizer.withDefaults()).frameOptions(f -> f.deny()))
            .authorizeHttpRequests(a -> a
                // Authorization is decided on the first (REQUEST) dispatch. A streaming response completes through an ASYNC dispatch, and
                // error pages through ERROR; re-checking there would fail with "response already committed" because the stateless
                // authentication does not exist on those dispatches.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/v3/api-docs", "/v3/api-docs/**").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(HttpStatus.UNAUTHORIZED.value());
                res.setContentType("application/problem+json");
                res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
            }));
        devAuth.ifAvailable(f -> http.addFilterBefore(f, AnonymousAuthenticationFilter.class));
        return http.build();
    }

    @Bean
    CurrentUsers currentUsers() {
        return () -> {
            Authentication a = SecurityContextHolder.getContext().getAuthentication();
            if (a == null || a instanceof AnonymousAuthenticationToken || !(a.getPrincipal() instanceof CurrentUser u)) {
                throw new DomainException("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED, "Authentication required");
            }
            return u;
        };
    }
}
