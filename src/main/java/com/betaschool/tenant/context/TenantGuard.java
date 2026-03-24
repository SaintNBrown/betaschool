package com.betaschool.tenant.context;

import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.repository.auth.JpaUserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * TenantGuard is called by every command and query handler to:
 * 1. Assert that the current user belongs to the school they're trying to access.
 * 2. Inject the school_id into every write operation.
 * 3. Enforce identity-level ownership so users can only access their own data.
 *
 * System admins bypass all tenant checks (school_id = null → unrestricted).
 */
@Component
@RequiredArgsConstructor
public class TenantGuard {
    /**
     * Returns the current school ID for use in queries/commands.
     * Throws if a non-admin tries to operate without a tenant context.
     */
    public Long requireSchoolId() {
        if (TenantContext.isSystemAdmin()) {
            throw new IllegalStateException(
                    "System admin must specify a schoolId explicitly for this operation");
        }
        Long id = TenantContext.getSchoolId();
        if (id == null) {
            throw new TenantAccessDeniedException("No tenant context found for this request");
        }
        return id;
    }

    /**
     * Returns current school ID, or null if system admin (unrestricted).
     * Used in query handlers: null means "all schools" (admin dashboard).
     */
    public Long getSchoolIdOrNull() {
        return TenantContext.getSchoolId();
    }

    /**
     * Asserts the given entity's school matches the request's school.
     * Always pass in the school_id stored on the entity being accessed.
     */
    public void assertBelongsToCurrentSchool(Long entitySchoolId) {
        if (TenantContext.isSystemAdmin()) return;   // admins can see all

        Long currentSchoolId = TenantContext.getSchoolId();
        if (currentSchoolId == null || !currentSchoolId.equals(entitySchoolId)) {
            throw new TenantAccessDeniedException(
                    "Access denied: resource does not belong to your school");
        }
    }

    /**
     * Asserts the current user has one of the required roles.
     */
    public void requireRole(String... allowedRoles) {
        String currentRole = TenantContext.getUserRole();
        for (String role : allowedRoles) {
            if (role.equals(currentRole)) return;
        }
        throw new TenantAccessDeniedException(
                "Access denied: role '" + currentRole + "' is not permitted for this operation");
    }

    /**
     * Identity ownership check for student/teacher data access.
     *
     * Reads the profileId from TenantContext (populated from the JWT at login time —
     * no database query needed on every request). SCHOOL_ADMIN and SYSTEM_ADMIN always pass.
     *
     * @param requestedProfileId  the student.id or teacher.id being requested
     * @param expectedProfileType STUDENT or TEACHER (used only for error messaging)
     */
    public void assertOwnerOrSchoolAdmin(Long requestedProfileId,
                                         UserProfileEntity.ProfileType expectedProfileType) {
        // Admins can access any profile in their school
        if (TenantContext.isSystemAdmin() || TenantContext.isSchoolAdmin()) return;

        Long currentProfileId = TenantContext.getProfileId();
        if (currentProfileId == null) {
            throw new TenantAccessDeniedException(
                    "Your account has no linked profile — contact your school administrator");
        }

        if (!currentProfileId.equals(requestedProfileId)) {
            throw new TenantAccessDeniedException(
                    "Access denied: you can only access your own data");
        }
    }

    /**
     * Returns the profile_id (student.id or teacher.id) from TenantContext.
     * Used by TEACHER role to resolve their own teacher.id for ownership checks.
     *
    public Long resolveCurrentProfileId(UserProfileEntity.ProfileType expectedType) {
        Long profileId = TenantContext.getProfileId();
        if (profileId == null) {
            throw new TenantAccessDeniedException(
                    "Your account has no linked profile — contact your school administrator");
        }
        return profileId;
    }
     */
}
