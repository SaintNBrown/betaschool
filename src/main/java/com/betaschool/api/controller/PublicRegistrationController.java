package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.auth.command.AuthCommand.SelfRegisterSchoolCommand;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.shared.CommandBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unauthenticated public endpoints for school self-registration.
 * All paths are prefixed /public/** and added to SecurityConfig PUBLIC_PATHS.
 * No JWT is required — these are called before any account exists.
 */
@RestController
@RequestMapping("/public/schools")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Public Registration", description = "Self-service school registration (no authentication required)")
public class PublicRegistrationController {

    private final CommandBus commandBus;
    private final JpaSchoolRepository schoolRepo;

    // ── Simple in-memory rate limiter for the slug-check endpoint ─────────
    // Keyed by client IP. Counter resets every 60 seconds.
    // This is intentionally lightweight — no external dependency.
    // For a multi-instance deployment, move to Redis with INCR + EXPIRE.
    private final ConcurrentHashMap<String, SlugCheckBucket> slugCheckBuckets =
            new ConcurrentHashMap<>();

    // ── Self-register ──────────────────────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Self-register a new school (no authentication required)",
            description = """
                   Creates a school tenant, seeds default configuration, creates the
                   SCHOOL_ADMIN account, and sends a welcome email. Returns the new school ID.
                   Validates slug and email uniqueness — returns 400 with a clear message if taken.
                   """)
    public ResponseEntity<ApiResponse<Long>> register(
            @Valid @RequestBody SelfRegisterRequest req) {

        Long schoolId = commandBus.dispatch(new SelfRegisterSchoolCommand(
                req.schoolName().trim(),
                req.schoolSlug().toLowerCase().trim(),
                req.schoolEmail().toLowerCase().trim(),
                req.schoolPhone(),
                req.schoolAddress(),
                req.adminFirstName().trim(),
                req.adminLastName().trim(),
                req.adminEmail().toLowerCase().trim(),
                req.adminPassword()));

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(schoolId));
    }

    // ── Slug availability check ───────────────────────────────────────────

    @GetMapping("/check-slug")
    @Operation(summary = "Check if a school slug is available (no authentication required)",
            description = "Rate-limited to 20 requests per IP per minute. Returns { available: true/false }.")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> checkSlug(
            @RequestParam String slug,
            @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor,
            jakarta.servlet.http.HttpServletRequest request) {

        String ip = forwardedFor != null ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();

        if (!allowSlugCheck(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.ok(Map.of("available", false)));
        }

        String normalised = slug.toLowerCase().trim();
        boolean available = !normalised.isBlank() && !schoolRepo.existsBySlug(normalised);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("available", available)));
    }

    // ── Rate limiter ──────────────────────────────────────────────────────

    private boolean allowSlugCheck(String ip) {
        long now = System.currentTimeMillis();
        SlugCheckBucket bucket = slugCheckBuckets.compute(ip, (k, b) -> {
            if (b == null || now - b.windowStart > 60_000L) {
                return new SlugCheckBucket(now);
            }
            return b;
        });
        return bucket.counter.incrementAndGet() <= 20;
    }

    private static final class SlugCheckBucket {
        final long windowStart;
        final AtomicInteger counter = new AtomicInteger(0);
        SlugCheckBucket(long windowStart) { this.windowStart = windowStart; }
    }

    // ── Request record ────────────────────────────────────────────────────

    public record SelfRegisterRequest(
            @NotBlank String schoolName,
            @NotBlank String schoolSlug,
            @Email @NotBlank String schoolEmail,
            String schoolPhone,
            String schoolAddress,
            @NotBlank String adminFirstName,
            @NotBlank String adminLastName,
            @Email @NotBlank String adminEmail,
            @NotBlank @Size(min = 8) String adminPassword
    ) {}
}