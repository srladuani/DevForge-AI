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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Deny by default. Only health/info and the OpenAPI document are public. The API is stateless (no cookies), so CSRF protection
 * does not apply; the production authenticator (OIDC) is added in a later milestone. Without dev auth every API call is a 401.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, OidcProperties.class})
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ObjectProvider<DevAuthFilter> devAuth, ObjectProvider<JwtDecoder> jwtDecoder, OidcProperties oidc, UserService users) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.contentTypeOptions(Customizer.withDefaults()).frameOptions(f -> f.deny())
                // the API only returns data: nothing it sends may be rendered, framed or leak the page address
                .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .authorizeHttpRequests(a -> a
                // Authorization is decided on the first (REQUEST) dispatch. A streaming response completes through an ASYNC dispatch, and
                // error pages through ERROR; re-checking there would fail with "response already committed" because the stateless
                // authentication does not exist on those dispatches.
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/v3/api-docs", "/v3/api-docs/**", "/api/v1/auth/config", "/api/v1/oauth/callback").permitAll()
                // operational data (queue depth, token spend, error rates) is for operators, not every signed-in user
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                // admin endpoints are also checked here, before any request body is read, so a non-admin learns nothing from validation errors
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                res.setStatus(HttpStatus.UNAUTHORIZED.value());
                res.setContentType("application/problem+json");
                res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"code\":\"UNAUTHENTICATED\"}");
            }).accessDeniedHandler((req, res, ex) -> {
                res.setStatus(HttpStatus.FORBIDDEN.value());
                res.setContentType("application/problem+json");
                // same code the service layer has always used for the admin area, so clients keep seeing ADMIN_REQUIRED
                String code = req.getRequestURI().startsWith("/api/v1/admin/") ? "ADMIN_REQUIRED" : "ACCESS_DENIED";
                res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"" + code + "\",\"detail\":\"Access denied\"}");
            }));
        devAuth.ifAvailable(f -> http.addFilterBefore(f, AnonymousAuthenticationFilter.class));
        JwtDecoder decoder = jwtDecoder.getIfAvailable();
        if (decoder != null) http.oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(new OidcAuthentication(oidc, users))));
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
