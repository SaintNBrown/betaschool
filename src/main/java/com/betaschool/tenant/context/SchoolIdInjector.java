package com.betaschool.tenant.context;

/**
 * Helper used by all command handlers to inject school_id from TenantContext
 * into newly created entities before saving.
 *
 * Usage in any handler:
 *   entity.setSchoolId(SchoolIdInjector.require());
 */
public final class SchoolIdInjector {

    private SchoolIdInjector() {}

    /**
     * Returns the current school ID.
     * System admins must pass an explicit schoolId via the command itself.
     */
    public static Long require() {
        if (TenantContext.isSystemAdmin()) {
            throw new IllegalStateException(
                    "System admins must provide explicit school_id in the command payload");
        }
        Long id = TenantContext.getSchoolId();
        if (id == null) {
            throw new TenantAccessDeniedException("No tenant context — cannot determine school_id");
        }
        return id;
    }

    /**
     * For handlers that accept an optional override (system admin path).
     * School users always get their own school_id regardless of what's passed.
     */
    public static Long resolveFor(Long explicitSchoolId) {
        if (TenantContext.isSystemAdmin()) {
            if (explicitSchoolId == null) {
                throw new TenantAccessDeniedException("System admin must provide schoolId");
            }
            return explicitSchoolId;
        }
        return require();
    }
}
