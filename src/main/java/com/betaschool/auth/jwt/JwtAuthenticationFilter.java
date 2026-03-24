package com.betaschool.auth.jwt;

import com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity.SchoolStatus;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.tenant.context.TenantContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final JpaSchoolRepository schoolRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain chain) throws ServletException, IOException {

        try {
            String token = extractToken(request);

            if (token != null && jwtService.isTokenValid(token)) {
                Claims claims = jwtService.extractAllClaims(token);

                Long userId    = Long.parseLong(claims.getSubject());
                String email   = claims.get("email",      String.class);
                String role    = claims.get("role",       String.class);
                Long schoolId  = claims.get("schoolId",   Long.class);
                String schoolSlug = claims.get("schoolSlug", String.class);
                Long profileId = claims.get("profileId",  Long.class);

                // ── School status guard ──────────────────────────────────
                // Every non-system-admin request checks school status live.
                if (schoolId != null) {
                    SchoolStatus status = schoolRepository.findStatusById(schoolId)
                            .orElse(null);

                    if (status == null || status == SchoolStatus.DEACTIVATED) {
                        sendSchoolError(response, "Your school account has been deactivated. "
                                + "Please contact BetaSchool support.");
                        return;
                    }

                    if (status == SchoolStatus.SUSPENDED) {
                        sendSchoolError(response, "Your school account is currently suspended. "
                                + "Please contact BetaSchool support.");
                        return;
                    }
                }

                // ── Populate TenantContext for this thread ───────────────
                TenantContext.set(schoolId, schoolSlug, role, userId, profileId);

                // ── Set Spring Security authentication ───────────────────
                var auth = new UsernamePasswordAuthenticationToken(
                        email, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

            chain.doFilter(request, response);

        } finally {
            // Always clear — prevents thread-pool leakage between requests
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private void sendSchoolError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\",\"status\":403}");
    }
}
