package com.flowops.config;

import com.flowops.common.error.ApiErrorWriter;
import com.flowops.common.error.ErrorCode;
import com.flowops.security.JwtAuthenticationFilter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The stateless bearer-token filter chain.
 *
 * <p>Authorization is coarse here on purpose — the chain separates "has a valid
 * token" from "may do this thing", and the second question is answered by
 * {@code AuthenticatedUser.requireRole} in the service layer, which compares role
 * <em>rank</em>. Spring's {@code hasRole('ADMIN')} would reject an {@code OWNER},
 * which is exactly backwards.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Paths reachable without an access token. {@code refresh} and {@code logout}
     * are here because they authenticate with the HttpOnly refresh cookie, not a
     * bearer token (contract §5.3, §5.4) — and logout must succeed even from a tab
     * whose token expired hours ago.
     */
    private static final String[] PUBLIC_PATHS = {
        "/health",
        "/health/**",
        "/actuator/**",
        "/error",
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/refresh",
        "/api/auth/logout",
        "/api/billing/plans",
        // A user who forgot their password is by definition logged out, so the
        // two reset endpoints must be reachable anonymously. Their protection is
        // the per-IP+email rate limit and the mandatory application/json content
        // type (CSRF preflight), not a bearer token.
        "/api/auth/forgot-password",
        "/api/auth/reset-password",
        "/oauth2/**",
        "/login/oauth2/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiErrorWriter errorWriter;
    private final String[] allowedOrigins;
    private final com.flowops.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
    private final com.flowops.security.oauth2.CustomOAuth2UserService customOAuth2UserService;
    private final com.flowops.security.oauth2.CustomOidcUserService customOidcUserService;
    private final com.flowops.security.oauth2.OAuth2AuthenticationSuccessHandler oauth2SuccessHandler;
    private final com.flowops.security.oauth2.OAuth2AuthenticationFailureHandler oauth2FailureHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiErrorWriter errorWriter,
            @Value("${flowops.cors.allowed-origins}") String allowedOrigins,
            com.flowops.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
            com.flowops.security.oauth2.CustomOAuth2UserService customOAuth2UserService,
            com.flowops.security.oauth2.CustomOidcUserService customOidcUserService,
            com.flowops.security.oauth2.OAuth2AuthenticationSuccessHandler oauth2SuccessHandler,
            com.flowops.security.oauth2.OAuth2AuthenticationFailureHandler oauth2FailureHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.errorWriter = errorWriter;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toArray(String[]::new);
        this.authorizationRequestRepository = authorizationRequestRepository;
        this.customOAuth2UserService = customOAuth2UserService;
        this.customOidcUserService = customOidcUserService;
        this.oauth2SuccessHandler = oauth2SuccessHandler;
        this.oauth2FailureHandler = oauth2FailureHandler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Redundant here and actively harmful: its cookie/repository model
                // assumes a browser session, while this API's CSRF defense is the
                // mandatory application/json content type on the two cookie-reading
                // endpoints, which forces a preflight (contract §1.4).
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        // Preflight carries no credential and must never be gated.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Public inbound webhooks authenticate with the URL token, not a
                        // bearer. Single-segment wildcards match exactly
                        // /api/webhooks/{workflowId}/{token} — POST only. Deliberately not
                        // in PUBLIC_PATHS (that matches every method and any depth).
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/*/*").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/oauth2/authorization")
                                .authorizationRequestRepository(authorizationRequestRepository))
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/login/oauth2/code/*"))
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customOAuth2UserService)
                                .oidcUserService(customOidcUserService))
                        .successHandler(oauth2SuccessHandler)
                        .failureHandler(oauth2FailureHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler()))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Missing or unusable credential. The JWT filter already emits the precise
     * {@code TOKEN_EXPIRED}/{@code TOKEN_INVALID} codes, so anything reaching here
     * had no {@code Authorization} header at all.
     */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, exception) -> errorWriter.write(
                request,
                response,
                ErrorCode.AUTHENTICATION_REQUIRED,
                ErrorCode.AUTHENTICATION_REQUIRED.defaultMessage());
    }

    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, exception) -> errorWriter.write(
                request,
                response,
                ErrorCode.FORBIDDEN_ROLE,
                ErrorCode.FORBIDDEN_ROLE.defaultMessage());
    }

    /**
     * CORS for the security filter chain, which runs <em>before</em> the
     * DispatcherServlet — so {@code WebConfig}'s MVC-level mapping alone would
     * never see a preflight. Both layers are configured from the same
     * {@code flowops.cors.allowed-origins} property, and Spring's CORS processor
     * skips a response that already carries the headers, so the overlap cannot
     * produce a duplicated {@code Access-Control-Allow-Origin}.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Explicit origins, never "*": credentials are allowed on this API and the
        // two combined are rejected by every browser.
        config.setAllowedOrigins(List.of(allowedOrigins));
        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("X-Request-Id"));
        // Required for the refresh cookie to be sent on the XHR.
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
