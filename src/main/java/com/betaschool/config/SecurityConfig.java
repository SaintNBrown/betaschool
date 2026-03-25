package com.betaschool.config;

import com.betaschool.auth.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    private static final String[] PUBLIC_PATHS = {
            "/auth/login",
            "/swagger-ui/**", "/api-docs/**", "/swagger-ui.html",
            "/auth/refresh",         // refresh token — no existing access token needed
            "/auth/forgot-password", // unauthenticated — user has lost their password
            "/auth/reset-password",
            "/actuator/health", "/actuator/info",
            "/error"  // Always permit /error — prevents security filter loop on exceptions
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()

                        // ── School Admin manages their school ────────────────────
                        .requestMatchers("/admin/schools/{schoolId}").hasAnyRole("SYSTEM_ADMIN", "SCHOOL_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/admin/schools/{schoolId}/users").hasAnyRole( "SYSTEM_ADMIN", "SCHOOL_ADMIN")

                        // ── System Admin only ────────────────────────────────────
                        .requestMatchers("/admin/**").hasRole("SYSTEM_ADMIN")

                        // ── Password change: any authenticated user ──────────────
                        .requestMatchers(HttpMethod.POST, "/auth/change-password").authenticated()

                        // ── Students ──────────────────────────────────────────────
                        // FIXED: replaced "/students/*/enroll/**" (** mid-path illegal in Spring 6.2+)
                        // Broad POST/PUT/DELETE matchers cover enroll sub-paths.
                        // Fine-grained ownership is enforced in handlers via TenantGuard/profileId.
                        .requestMatchers(HttpMethod.POST,   "/students").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.PUT,    "/students/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.POST,   "/students/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/students/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Teachers ──────────────────────────────────────────────
                        // FIXED: replaced "/teachers/*/assign/**" (same issue)
                        .requestMatchers(HttpMethod.POST,   "/teachers").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.PUT,    "/teachers/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.POST,   "/teachers/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Sessions ─────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST,   "/sessions").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.PUT,    "/sessions/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.PATCH,  "/sessions/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.POST,   "/sessions/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Classes ───────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST,   "/classes").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.POST,   "/classes/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.PATCH,  "/classes/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Subjects ──────────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST,   "/subjects").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Examinations ──────────────────────────────────────────
                        .requestMatchers(HttpMethod.POST,   "/examinations").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        // Results submission: TEACHER or admin
                        // FIXED: replaced "/examinations/*/results/**" — use broad matcher
                        .requestMatchers("/examinations/**").hasAnyRole("TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Timetables ────────────────────────────────────────────
                        // FIXED: removed "/timetables/**/history" — ** in the middle is illegal.
                        // The history endpoint GET /timetables/{id}/history is covered by the
                        // broad GET matcher; restrict it further with @PreAuthorize on the controller.
                        .requestMatchers(HttpMethod.POST,   "/timetables/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/timetables/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET,    "/timetables/**").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── Read access ───────────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/students/**").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/teachers/**").hasAnyRole("TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/sessions").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/sessions/**").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/classes").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/classes/**").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/subjects").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/subjects/**").hasAnyRole("STUDENT", "TEACHER", "SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        // ── User management ───────────────────────────────────────
                        .requestMatchers(HttpMethod.GET, "/schools/config/score").hasAnyRole("SCHOOL_ADMIN", "TEACHER", "STUDENT")
                        .requestMatchers("/schools/**").hasAnyRole("SCHOOL_ADMIN", "SYSTEM_ADMIN")

                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @org.springframework.beans.factory.annotation.Value(
            "${cors.allowed-origins}")
    private String allowedOriginsRaw;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        List<String> origins = List.of(allowedOriginsRaw.split(","));
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
