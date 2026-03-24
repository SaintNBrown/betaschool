package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.auth.command.AuthCommand.*;
import com.betaschool.auth.query.AuthQuery.GetActiveSchoolsQuery;
import com.betaschool.auth.query.AuthQuery.GetAllSchoolsQuery;
import com.betaschool.auth.query.AuthQuery.GetSchoolByIdQuery;
import com.betaschool.auth.query.AuthQuery.GetSchoolUsersQuery;
import com.betaschool.auth.query.SchoolResult.SchoolDetail;
import com.betaschool.auth.query.SchoolResult.SchoolSummary;
import com.betaschool.auth.query.SchoolResult.UserSummary;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Auth & Schools", description = "Authentication and school (tenant) management")
public class AuthController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    // ── Authentication ────────────────────────────────────────────────────

    @PostMapping("/auth/login")
    @Operation(summary = "Login — returns JWT access + refresh tokens")
    public ResponseEntity<ApiResponse<LoginResult>> login(@Valid @RequestBody LoginRequest req) {
        LoginResult result = commandBus.dispatch(new LoginCommand(req.email(), req.password()));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/auth/refresh")
    @Operation(summary = "Exchange a valid refresh token for a new access token",
               description = "Pass the refreshToken received at login. Returns a new accessToken and refreshToken. " +
                             "The old refresh token is consumed — store the new one.")
    public ResponseEntity<ApiResponse<RefreshResult>> refreshToken(
            @Valid @RequestBody RefreshRequest req) {
        RefreshResult result = commandBus.dispatch(new RefreshTokenCommand(req.refreshToken()));
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    /**
     * Issue 4: Change own password. Available to every authenticated user.
     * Requires supplying the current password for verification.
     */
    @PostMapping("/auth/change-password")
    @Operation(summary = "Change own password (any authenticated user)")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest req) {
        commandBus.dispatch(new ChangePasswordCommand(null, req.currentPassword(), req.newPassword()));
        return ResponseEntity.ok(ApiResponse.noContent("Password changed successfully"));
    }

    /**
     * Step 1 — Forgot password.
     * Always returns 200 regardless of whether the email exists (prevents enumeration).
     * A reset link is emailed if the account is found and active.
     */
    @PostMapping("/auth/forgot-password")
    @Operation(summary = "Request a password reset email (unauthenticated)",
               description = "Submit the account email address. If an active account exists, " +
                             "a time-limited reset link will be sent. Always returns 200 to " +
                             "prevent user enumeration.")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req) {
        commandBus.dispatch(new ForgotPasswordCommand(req.email()));
        return ResponseEntity.ok(ApiResponse.noContent(
                "If an account with that email exists, a reset link has been sent."));
    }

    /**
     * Step 2 — Reset password using the token from the email.
     */
    @PostMapping("/auth/reset-password")
    @Operation(summary = "Reset password using a token received by email (unauthenticated)",
               description = "Submit the token from the reset email and the new password. " +
                             "The token is single-use and expires after the configured window.")
    public ResponseEntity<ApiResponse<Void>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest req) {
        commandBus.dispatch(new ResetPasswordCommand(
                req.token(), req.newPassword()));
        return ResponseEntity.ok(ApiResponse.noContent("Password has been reset. You can now log in."));
    }

    // ── School Management (SYSTEM_ADMIN) ─────────────────────────────────

    @PostMapping("/admin/schools")
    @Operation(summary = "[SYSTEM_ADMIN] Register a new school and create its admin user")
    public ResponseEntity<ApiResponse<Long>> registerSchool(@Valid @RequestBody RegisterSchoolRequest req) {
        Long schoolId = commandBus.dispatch(new RegisterSchoolCommand(
                req.name(), req.slug(), req.email(), req.phone(), req.address(),
                req.adminEmail(), req.adminPassword(), req.adminSurname(), req.adminOtherNames()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(schoolId));
    }

    @GetMapping("/admin/schools")
    @Operation(summary = "[SYSTEM_ADMIN] List all registered schools")
    public ResponseEntity<ApiResponse<Page<SchoolSummary>>> getAllSchools(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetAllSchoolsQuery(pageable))));
    }

    @GetMapping("/admin/schools/active")
    @Operation(summary = "[SYSTEM_ADMIN] List all active schools")
    public ResponseEntity<ApiResponse<List<SchoolSummary>>> getActiveSchools() {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetActiveSchoolsQuery())));
    }

    @GetMapping("/admin/schools/{schoolId}")
    @Operation(summary = "[SYSTEM_ADMIN | SCHOOL_ADMIN] Get school details")
    public ResponseEntity<ApiResponse<SchoolDetail>> getSchool(@PathVariable Long schoolId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetSchoolByIdQuery(schoolId))));
    }

    @PatchMapping("/admin/schools/{schoolId}/suspend")
    @Operation(summary = "[SYSTEM_ADMIN] Temporarily suspend a school")
    public ResponseEntity<ApiResponse<Void>> suspendSchool(
            @PathVariable Long schoolId,
            @RequestParam(required = false) String reason) {
        commandBus.dispatch(new SuspendSchoolCommand(schoolId, reason));
        return ResponseEntity.ok(ApiResponse.noContent("School suspended"));
    }

    @PatchMapping("/admin/schools/{schoolId}/reactivate")
    @Operation(summary = "[SYSTEM_ADMIN] Reactivate a suspended school")
    public ResponseEntity<ApiResponse<Void>> reactivateSchool(@PathVariable Long schoolId) {
        commandBus.dispatch(new ReactivateSchoolCommand(schoolId));
        return ResponseEntity.ok(ApiResponse.noContent("School reactivated"));
    }

    @PatchMapping("/admin/schools/{schoolId}/deactivate")
    @Operation(summary = "[SYSTEM_ADMIN] Permanently deactivate a school (irreversible)")
    public ResponseEntity<ApiResponse<Void>> deactivateSchool(
            @PathVariable Long schoolId,
            @RequestParam String reason) {
        commandBus.dispatch(new DeactivateSchoolCommand(schoolId, reason));
        return ResponseEntity.ok(ApiResponse.noContent("School permanently deactivated"));
    }

    // ── School User Management ────────────────────────────────────────────

    @GetMapping("/admin/schools/{schoolId}/users")
    @Operation(summary = "[SYSTEM_ADMIN | SCHOOL_ADMIN] Get users of a school")
    public ResponseEntity<ApiResponse<List<UserSummary>>> getSchoolUsers(@PathVariable Long schoolId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetSchoolUsersQuery(schoolId))));
    }

    @PostMapping("/schools/{schoolId}/users")
    @Operation(summary = "[SCHOOL_ADMIN] Create a teacher or student user account")
    public ResponseEntity<ApiResponse<Long>> createUser(
            @PathVariable Long schoolId,
            @Valid @RequestBody CreateUserRequest req) {
        Long id = commandBus.dispatch(new CreateSchoolUserCommand(
                schoolId, req.email(), req.password(), req.role(), req.profileId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PatchMapping("/schools/users/{userId}/deactivate")
    @Operation(summary = "[SCHOOL_ADMIN] Deactivate a user in your school")
    public ResponseEntity<ApiResponse<Void>> deactivateUser(@PathVariable Long userId) {
        commandBus.dispatch(new DeactivateUserCommand(userId, null));
        return ResponseEntity.ok(ApiResponse.noContent("User deactivated"));
    }

    // ── Request body records ──────────────────────────────────────────────

    public record LoginRequest(String email, String password) {}

    public record RefreshRequest(
            @jakarta.validation.constraints.NotBlank String refreshToken) {}

    public record ChangePasswordRequest(
            @jakarta.validation.constraints.NotBlank String currentPassword,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 8) String newPassword) {}

    public record ForgotPasswordRequest(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Email String email) {}

    public record ResetPasswordRequest(
            @jakarta.validation.constraints.NotBlank String token,
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(min = 8) String newPassword) {}

    public record RegisterSchoolRequest(
            String name, String slug, String email, String phone, String address,
            String adminEmail, String adminPassword, String adminSurname, String adminOtherNames) {}

    public record CreateUserRequest(
            String email, String password, String role, Long profileId) {}
}
