package com.betaschool.auth.command;

import com.betaschool.shared.Command;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public sealed interface AuthCommand {

    // ── Authentication ────────────────────────────────────────────

    record LoginCommand(
            @Email @NotBlank String email,
            @NotBlank String password
    ) implements Command<LoginResult>, AuthCommand {}

    record LoginResult(
            String accessToken,
            String refreshToken,
            String role,
            Long schoolId,
            String schoolName,
            Long profileId       // student.id or teacher.id; null for SCHOOL_ADMIN and SYSTEM_ADMIN
    ) {}

    // ── School Registration (system admin only) ───────────────────

    record RegisterSchoolCommand(
            @NotBlank String name,
            @NotBlank String slug,
            @Email @NotBlank String email,
            String phone,
            String address,
            // Initial school admin credentials
            @Email @NotBlank String adminEmail,
            @NotBlank @Size(min = 8) String adminPassword,
            @NotBlank String adminSurname,
            @NotBlank String adminOtherNames
    ) implements Command<Long>, AuthCommand {}

    // ── School Status Management (system admin only) ──────────────

    record SuspendSchoolCommand(
            Long schoolId,
            String reason
    ) implements Command<Void>, AuthCommand {}

    record ReactivateSchoolCommand(
            Long schoolId
    ) implements Command<Void>, AuthCommand {}

    record DeactivateSchoolCommand(
            Long schoolId,
            @NotBlank String reason
    ) implements Command<Void>, AuthCommand {}

    // ── User Management (school admin only) ──────────────────────

    record CreateSchoolUserCommand(
            Long schoolId,         // set by handler from TenantContext
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String role, // TEACHER or STUDENT
            Long profileId         // links to teacher.id or student.id
    ) implements Command<Long>, AuthCommand {}

    record DeactivateUserCommand(
            Long userId,
            Long schoolId          // set by handler from TenantContext
    ) implements Command<Void>, AuthCommand {}

    /** Reactivates a previously deactivated (INACTIVE) user account. */
    record ReactivateUserCommand(
            Long userId
    ) implements Command<Void>, AuthCommand {}

    record ChangePasswordCommand(
            Long userId,
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8) String newPassword
    ) implements Command<Void>, AuthCommand {}

    /** Exchange a valid refresh token for a new access token. */
    record RefreshTokenCommand(
            @NotBlank String refreshToken
    ) implements Command<RefreshResult>, AuthCommand {}

    record RefreshResult(
            String accessToken,
            String refreshToken
    ) {}

    /**
     * Step 1 of forgot-password: user submits their email address.
     * Always returns success (even if email not found) to prevent user enumeration.
     */
    record ForgotPasswordCommand(
            @Email @NotBlank String email
    ) implements Command<Void>, AuthCommand {}

    /**
     * Step 2 of forgot-password: user submits the token from their email + new password.
     */
    record ResetPasswordCommand(
            @NotBlank String token,
            @NotBlank @Size(min = 8) String newPassword
    ) implements Command<Void>, AuthCommand {}
}
