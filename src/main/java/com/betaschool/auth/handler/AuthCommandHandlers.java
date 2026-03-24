package com.betaschool.auth.handler;

import com.betaschool.auth.command.AuthCommand.*;
import com.betaschool.auth.jwt.JwtService;
import com.betaschool.auth.jwt.JwtService.TokenClaims;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity.UserRole;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity.UserStatus;
import com.betaschool.infrastructure.persistence.entity.auth.SchoolAuditLogEntity;
import com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity;
import com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity.SchoolStatus;
import com.betaschool.infrastructure.persistence.entity.auth.UserAuditLogEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity.ProfileType;
import com.betaschool.infrastructure.persistence.repository.auth.JpaAppUserRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolAuditLogRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaUserAuditLogRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaUserProfileRepository;
import com.betaschool.infrastructure.persistence.entity.SchoolScoreConfigEntity;
import com.betaschool.infrastructure.persistence.repository.JpaSchoolScoreConfigRepository;
import com.betaschool.infrastructure.persistence.entity.auth.PasswordResetTokenEntity;
import com.betaschool.infrastructure.persistence.repository.auth.JpaPasswordResetTokenRepository;
import com.betaschool.infrastructure.mail.EmailService;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantAccessDeniedException;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Slf4j
public class AuthCommandHandlers {

    // ── Login ─────────────────────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class LoginHandler implements CommandHandler<LoginCommand, LoginResult> {

        private final JpaAppUserRepository userRepo;
        private final JpaUserProfileRepository userProfileRepo;
        private final PasswordEncoder passwordEncoder;
        private final JwtService jwtService;

        @Override
        public LoginResult handle(LoginCommand cmd) {
            AppUserEntity user = userRepo.findByEmailWithSchool(cmd.email())
                    .orElseThrow(() -> new TenantAccessDeniedException("Invalid email or password"));

            if (!user.isActive()) {
                throw new TenantAccessDeniedException("Your account is not active");
            }

            if (!passwordEncoder.matches(cmd.password(), user.getPasswordHash())) {
                throw new TenantAccessDeniedException("Invalid email or password");
            }

            // School users: validate their school is active at login time
            if (!user.isSystemAdmin()) {
                SchoolEntity school = user.getSchool();
                if (school == null || !school.isActive()) {
                    throw new TenantAccessDeniedException(
                            "Your school account is not active. Please contact BetaSchool support.");
                }
            }

            // Update last login
            user.setLastLoginAt(OffsetDateTime.now());
            userRepo.save(user);

            Long schoolId   = user.isSystemAdmin() ? null : user.getSchool().getId();
            String schoolSlug = user.isSystemAdmin() ? null : user.getSchool().getSlug();
            String schoolName = user.isSystemAdmin() ? "BetaSchool System" : user.getSchool().getName();

            // Resolve profileId — the student.id or teacher.id linked to this account.
            // SCHOOL_ADMIN and SYSTEM_ADMIN have no profile record; profileId stays null.
            Long profileId = userProfileRepo.findByUserId(user.getId())
                    .map(UserProfileEntity::getProfileId)
                    .orElse(null);

            TokenClaims claims = new TokenClaims(
                    user.getId(), user.getEmail(), user.getRole().name(),
                    schoolId, schoolSlug, profileId);

            String accessToken  = jwtService.generateToken(claims);
            String refreshToken = jwtService.generateRefreshToken(user.getId());

            log.info("Login: user={} role={} school={} profileId={}",
                    user.getEmail(), user.getRole(), schoolId, profileId);

            return new LoginResult(accessToken, refreshToken,
                    user.getRole().name(), schoolId, schoolName, profileId);
        }
    }

    // ── Change Password (any authenticated user) ─────────────────────────

    /**
     * Issue 4: Implements the ChangePasswordCommand that previously had no handler.
     * Any authenticated user (STUDENT, TEACHER, SCHOOL_ADMIN, SYSTEM_ADMIN) may
     * change their own password by providing their current password for verification.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class ChangePasswordHandler implements CommandHandler<ChangePasswordCommand, Void> {

        private final JpaAppUserRepository userRepo;
        private final JpaUserAuditLogRepository userAuditRepo;
        private final PasswordEncoder passwordEncoder;

        @Override
        public Void handle(ChangePasswordCommand cmd) {
            Long currentUserId = TenantContext.getUserId();
            if (currentUserId == null) {
                throw new TenantAccessDeniedException("No authenticated user context");
            }

            AppUserEntity user = userRepo.findById(currentUserId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", currentUserId));

            if (!passwordEncoder.matches(cmd.currentPassword(), user.getPasswordHash())) {
                throw new TenantAccessDeniedException("Current password is incorrect");
            }

            if (cmd.currentPassword().equals(cmd.newPassword())) {
                throw new BusinessRuleViolationException(
                        "New password must be different from the current password");
            }

            user.setPasswordHash(passwordEncoder.encode(cmd.newPassword()));
            userRepo.save(user);

            // Audit
            userAuditRepo.save(UserAuditLogEntity.builder()
                    .userId(user.getId())
                    .schoolId(user.getSchool() != null ? user.getSchool().getId() : null)
                    .action("PASSWORD_CHANGE")
                    .build());

            log.info("Password changed for user={}", user.getEmail());
            return null;
        }
    }

    // ── Refresh Token ─────────────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    public static class RefreshTokenHandler
            implements CommandHandler<RefreshTokenCommand, RefreshResult> {

        private final JpaAppUserRepository userRepo;
        private final JpaUserProfileRepository userProfileRepo;
        private final JwtService jwtService;

        @Override
        public RefreshResult handle(RefreshTokenCommand cmd) {
            // Validate the refresh token
            if (!jwtService.isTokenValid(cmd.refreshToken())) {
                throw new TenantAccessDeniedException("Refresh token is invalid or expired. Please log in again.");
            }

            io.jsonwebtoken.Claims claims;
            try {
                claims = jwtService.extractAllClaims(cmd.refreshToken());
            } catch (Exception e) {
                throw new TenantAccessDeniedException("Refresh token could not be parsed. Please log in again.");
            }

            // Verify it is actually a refresh token, not an access token
            String tokenType = claims.get("type", String.class);
            if (!"refresh".equals(tokenType)) {
                throw new TenantAccessDeniedException("Token is not a refresh token.");
            }

            Long userId = Long.parseLong(claims.getSubject());

            AppUserEntity user = userRepo.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", userId));

            if (user.getStatus() != AppUserEntity.UserStatus.ACTIVE) {
                throw new TenantAccessDeniedException("Account is not active.");
            }

            // Rebuild token claims identical to login
            Long schoolId   = user.getSchool() != null ? user.getSchool().getId()   : null;
            String schoolSlug = user.getSchool() != null ? user.getSchool().getSlug() : null;
            Long profileId  = userProfileRepo.findByUserId(userId)
                    .map(p -> p.getProfileId()).orElse(null);

            JwtService.TokenClaims tokenClaims = new JwtService.TokenClaims(
                    userId, user.getEmail(), user.getRole().name(),
                    schoolId, schoolSlug, profileId);

            String newAccessToken  = jwtService.generateToken(tokenClaims);
            String newRefreshToken = jwtService.generateRefreshToken(userId);

            log.info("Token refreshed for user={}", user.getEmail());
            return new RefreshResult(newAccessToken, newRefreshToken);
        }
    }

    // ── Register School (SYSTEM_ADMIN only) ──────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class RegisterSchoolHandler implements CommandHandler<RegisterSchoolCommand, Long> {

        private final JpaSchoolRepository schoolRepo;
        private final JpaAppUserRepository userRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;
        private final PasswordEncoder passwordEncoder;
        private final TenantGuard tenantGuard;

        @Override
        public Long handle(RegisterSchoolCommand cmd) {
            tenantGuard.requireRole("SYSTEM_ADMIN");

            if (schoolRepo.existsBySlug(cmd.slug())) {
                throw new BusinessRuleViolationException("School slug '" + cmd.slug() + "' is already taken");
            }
            if (schoolRepo.existsByEmail(cmd.email())) {
                throw new BusinessRuleViolationException("A school with this email already exists");
            }
            if (userRepo.existsByEmail(cmd.adminEmail())) {
                throw new BusinessRuleViolationException("Admin email '" + cmd.adminEmail() + "' is already registered");
            }

            // Create the school (tenant)
            SchoolEntity school = SchoolEntity.builder()
                    .name(cmd.name())
                    .slug(cmd.slug())
                    .email(cmd.email())
                    .phone(cmd.phone())
                    .address(cmd.address())
                    .status(SchoolStatus.ACTIVE)
                    .activatedAt(OffsetDateTime.now())
                    .build();
            school = schoolRepo.save(school);

            // Seed a default score config for the new school
            scoreConfigRepo.save(SchoolScoreConfigEntity.builder()
                    .schoolId(school.getId())
                    .build());

            // Create the school's admin user
            AppUserEntity admin = AppUserEntity.builder()
                    .school(school)
                    .email(cmd.adminEmail())
                    .passwordHash(passwordEncoder.encode(cmd.adminPassword()))
                    .role(UserRole.SCHOOL_ADMIN)
                    .status(UserStatus.ACTIVE)
                    .build();
            userRepo.save(admin);

            log.info("Registered school: id={} slug={} adminEmail={}", school.getId(), school.getSlug(), cmd.adminEmail());
            return school.getId();
        }
    }

    // ── Suspend School ────────────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class SuspendSchoolHandler implements CommandHandler<SuspendSchoolCommand, Void> {

        private final JpaSchoolRepository schoolRepo;
        private final JpaSchoolAuditLogRepository auditRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Void handle(SuspendSchoolCommand cmd) {
            tenantGuard.requireRole("SYSTEM_ADMIN");

            SchoolEntity school = schoolRepo.findById(cmd.schoolId())
                    .orElseThrow(() -> new ResourceNotFoundException("School", cmd.schoolId()));

            if (school.getStatus() == SchoolStatus.DEACTIVATED) {
                throw new BusinessRuleViolationException("Cannot suspend a deactivated school");
            }

            SchoolStatus previous = school.getStatus();
            school.setStatus(SchoolStatus.SUSPENDED);
            school.setDeactivationReason(cmd.reason());
            schoolRepo.save(school);

            auditRepo.save(SchoolAuditLogEntity.builder()
                    .schoolId(cmd.schoolId())
                    .action("SUSPENDED")
                    .previousStatus(previous.name())
                    .newStatus(SchoolStatus.SUSPENDED.name())
                    .reason(cmd.reason())
                    .build());

            log.warn("School SUSPENDED: id={} name={} reason={}", school.getId(), school.getName(), cmd.reason());
            return null;
        }
    }

    // ── Reactivate School ─────────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class ReactivateSchoolHandler implements CommandHandler<ReactivateSchoolCommand, Void> {

        private final JpaSchoolRepository schoolRepo;
        private final JpaSchoolAuditLogRepository auditRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Void handle(ReactivateSchoolCommand cmd) {
            tenantGuard.requireRole("SYSTEM_ADMIN");

            SchoolEntity school = schoolRepo.findById(cmd.schoolId())
                    .orElseThrow(() -> new ResourceNotFoundException("School", cmd.schoolId()));

            if (school.getStatus() == SchoolStatus.DEACTIVATED) {
                throw new BusinessRuleViolationException(
                        "Cannot reactivate a permanently deactivated school. Create a new registration.");
            }

            SchoolStatus previous = school.getStatus();
            school.setStatus(SchoolStatus.ACTIVE);
            school.setDeactivationReason(null);
            school.setActivatedAt(OffsetDateTime.now());
            schoolRepo.save(school);

            auditRepo.save(SchoolAuditLogEntity.builder()
                    .schoolId(cmd.schoolId())
                    .action("REACTIVATED")
                    .previousStatus(previous.name())
                    .newStatus(SchoolStatus.ACTIVE.name())
                    .build());

            log.info("School REACTIVATED: id={} name={}", school.getId(), school.getName());
            return null;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class DeactivateSchoolHandler implements CommandHandler<DeactivateSchoolCommand, Void> {

        private final JpaSchoolRepository schoolRepo;
        private final JpaAppUserRepository userRepo;
        private final JpaSchoolAuditLogRepository auditRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Void handle(DeactivateSchoolCommand cmd) {
            tenantGuard.requireRole("SYSTEM_ADMIN");

            SchoolEntity school = schoolRepo.findById(cmd.schoolId())
                    .orElseThrow(() -> new ResourceNotFoundException("School", cmd.schoolId()));

            SchoolStatus previous = school.getStatus();
            school.setStatus(SchoolStatus.DEACTIVATED);
            school.setDeactivatedAt(OffsetDateTime.now());
            school.setDeactivationReason(cmd.reason());
            schoolRepo.save(school);

            userRepo.findBySchoolId(cmd.schoolId()).forEach(u -> {
                u.setStatus(UserStatus.INACTIVE);   // Issue 10: use INACTIVE not SUSPENDED
                userRepo.save(u);
            });

            auditRepo.save(SchoolAuditLogEntity.builder()
                    .schoolId(cmd.schoolId())
                    .action("DEACTIVATED")
                    .previousStatus(previous.name())
                    .newStatus(SchoolStatus.DEACTIVATED.name())
                    .reason(cmd.reason())
                    .build());

            log.warn("School DEACTIVATED: id={} name={} reason={}",
                    school.getId(), school.getName(), cmd.reason());
            return null;
        }
    }

    // ── Create School User (SCHOOL_ADMIN only) ────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateSchoolUserHandler implements CommandHandler<CreateSchoolUserCommand, Long> {

        private final JpaSchoolRepository schoolRepo;
        private final JpaAppUserRepository userRepo;
        private final JpaUserProfileRepository profileRepo;
        private final PasswordEncoder passwordEncoder;
        private final TenantGuard tenantGuard;

        @Override
        public Long handle(CreateSchoolUserCommand cmd) {
            tenantGuard.requireRole("SYSTEM_ADMIN", "SCHOOL_ADMIN");

            // School admins can only create users in their own school
            Long schoolId = TenantContext.isSystemAdmin() ? cmd.schoolId()
                    : tenantGuard.requireSchoolId();

            SchoolEntity school = schoolRepo.findById(schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("School", schoolId));

            if (userRepo.existsByEmail(cmd.email())) {
                throw new BusinessRuleViolationException("Email '" + cmd.email() + "' is already registered");
            }

            UserRole role;
            try {
                role = UserRole.valueOf(cmd.role());
            } catch (IllegalArgumentException e) {
                throw new BusinessRuleViolationException("Invalid role: " + cmd.role());
            }

            if (role == UserRole.SYSTEM_ADMIN) {
                throw new BusinessRuleViolationException("Cannot create system admin through this endpoint");
            }

            AppUserEntity user = AppUserEntity.builder()
                    .school(school)
                    .email(cmd.email())
                    .passwordHash(passwordEncoder.encode(cmd.password()))
                    .role(role)
                    .status(UserStatus.ACTIVE)
                    .build();
            user = userRepo.save(user);

            // Link to student/teacher profile.
            // SCHOOL_ADMIN has no profile record — skip entirely.
            // TEACHER and STUDENT must always provide a profileId, otherwise
            // the account is created but can never be linked to a profile,
            // which causes every subsequent request to return 403.
            if (role == UserRole.TEACHER || role == UserRole.STUDENT) {
                if (cmd.profileId() == null) {
                    throw new BusinessRuleViolationException(
                            "profileId is required when creating a " + role + " account. "
                                    + "Create the " + role.name().toLowerCase() + " record first "
                                    + "(POST /teachers or POST /students), then pass the returned id as profileId.");
                }
                ProfileType profileType = role == UserRole.STUDENT
                        ? ProfileType.STUDENT : ProfileType.TEACHER;
                profileRepo.save(UserProfileEntity.builder()
                        .user(user)
                        .profileType(profileType)
                        .profileId(cmd.profileId())
                        .build());
            }

            return user.getId();
        }
    }

    // ── Deactivate User ────────────────────────────────────────────────────


    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class DeactivateUserHandler implements CommandHandler<DeactivateUserCommand, Void> {

        private final JpaAppUserRepository userRepo;
        private final JpaUserAuditLogRepository userAuditRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Void handle(DeactivateUserCommand cmd) {
            tenantGuard.requireRole("SYSTEM_ADMIN", "SCHOOL_ADMIN");

            AppUserEntity user = userRepo.findById(cmd.userId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", cmd.userId()));

            // School admins can only deactivate users in their own school
            if (!TenantContext.isSystemAdmin()) {
                tenantGuard.assertBelongsToCurrentSchool(
                        user.getSchool() != null ? user.getSchool().getId() : null);
            }

            // Issue 10: INACTIVE = intentional admin action; SUSPENDED = platform-level hold
            user.setStatus(UserStatus.INACTIVE);
            userRepo.save(user);

            // Issue 10: persist audit trail for user deactivation
            Long performedBy = TenantContext.getUserId();
            userAuditRepo.save(UserAuditLogEntity.builder()
                    .userId(user.getId())
                    .schoolId(user.getSchool() != null ? user.getSchool().getId() : null)
                    .action("DEACTIVATED")
                    .build());

            log.info("User DEACTIVATED: id={} email={} by={}",
                    user.getId(), user.getEmail(), performedBy);
            return null;
        }
    }

    // ── Forgot Password — Step 1: issue token ────────────────────────────

    /**
     * Generates a secure single-use reset token, stores its SHA-256 hash,
     * and fires an async email to the user.
     *
     * Security: always returns Void (no error) even when the email doesn't exist
     * to prevent user-enumeration attacks.  The caller should show the same
     * "check your email" message regardless of outcome.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class ForgotPasswordHandler implements CommandHandler<ForgotPasswordCommand, Void> {

        private final JpaAppUserRepository userRepo;
        private final JpaPasswordResetTokenRepository tokenRepo;
        private final EmailService emailService;

        @org.springframework.beans.factory.annotation.Value("${app.mail.frontend-base-url}")
        private String frontendBaseUrl;

        @org.springframework.beans.factory.annotation.Value("${app.password-reset.token-expiry-minutes:30}")
        private int expiryMinutes;

        @Override
        public Void handle(ForgotPasswordCommand cmd) {
            // Always return success — no exception for unknown email
            userRepo.findByEmail(cmd.email()).ifPresent(user -> {

                if (!user.isActive()) {
                    // Don't issue tokens for inactive accounts — silently skip
                    log.warn("Password reset requested for inactive user={}", cmd.email());
                    return;
                }

                // 1. Generate a cryptographically-secure 32-byte random token
                byte[] randomBytes = new byte[32];
                new java.security.SecureRandom().nextBytes(randomBytes);
                String rawToken = java.util.Base64.getUrlEncoder()
                        .withoutPadding().encodeToString(randomBytes);

                // 2. Hash it — only the hash goes in the DB
                String tokenHash = hashToken(rawToken);

                // 3. Invalidate any previous token for this user (one-at-a-time)
                tokenRepo.deleteByUserId(user.getId());

                // 4. Persist the hashed token
                tokenRepo.save(PasswordResetTokenEntity.builder()
                        .user(user)
                        .tokenHash(tokenHash)
                        .expiresAt(OffsetDateTime.now().plusMinutes(expiryMinutes))
                        .build());

                // 5. Build the reset link pointing at the frontend
                String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;

                // 6. Resolve the school name for the email subject/branding
                String schoolName = (user.getSchool() != null)
                        ? user.getSchool().getName()
                        : "BetaSchool";

                // 7. Fire the email asynchronously — won't block the HTTP response
                emailService.sendPasswordResetEmail(
                        user.getEmail(), schoolName, resetLink, expiryMinutes);

                log.info("Password reset token issued for user={}", cmd.email());
            });
            return null;
        }
    }

    // ── Forgot Password — Step 2: consume token and set new password ─────

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class ResetPasswordHandler implements CommandHandler<ResetPasswordCommand, Void> {

        private final JpaAppUserRepository userRepo;
        private final JpaPasswordResetTokenRepository tokenRepo;
        private final JpaUserAuditLogRepository userAuditRepo;
        private final PasswordEncoder passwordEncoder;

        @Override
        public Void handle(ResetPasswordCommand cmd) {
            String tokenHash = hashToken(cmd.token());

            PasswordResetTokenEntity resetToken = tokenRepo.findByTokenHash(tokenHash)
                    .orElseThrow(() -> new BusinessRuleViolationException(
                            "This password reset link is invalid or has already been used."));

            if (!resetToken.isValid()) {
                throw new BusinessRuleViolationException(
                        resetToken.isExpired()
                                ? "This password reset link has expired. Please request a new one."
                                : "This password reset link has already been used.");
            }

            AppUserEntity user = resetToken.getUser();

            if (!user.isActive()) {
                throw new TenantAccessDeniedException("This account is not active.");
            }

            // Set the new password
            user.setPasswordHash(passwordEncoder.encode(cmd.newPassword()));
            userRepo.save(user);

            // Mark token consumed — prevents replay
            resetToken.setUsed(true);
            tokenRepo.save(resetToken);

            // Audit trail
            userAuditRepo.save(UserAuditLogEntity.builder()
                    .userId(user.getId())
                    .schoolId(user.getSchool() != null ? user.getSchool().getId() : null)
                    .action("PASSWORD_RESET")
                    .build());

            log.info("Password reset completed for user={}", user.getEmail());
            return null;
        }
    }

    // ── Shared helper ─────────────────────────────────────────────────────

    /**
     * SHA-256 hex digest of the raw token string.
     * Used by both ForgotPasswordHandler (store) and ResetPasswordHandler (lookup).
     */
    static String hashToken(String rawToken) {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec — this can never happen
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}