package com.betaschool.tenant.context;

/**
 * Thread-local tenant context. Populated by JwtAuthenticationFilter on every request.
 * Every repository and service reads from this to scope queries to the current school.
 *
 * System admins have schoolId = null — they bypass tenant filtering.
 */
public final class TenantContext {

    private static final ThreadLocal<Long>   SCHOOL_ID   = new InheritableThreadLocal<>();
    private static final ThreadLocal<String> SCHOOL_SLUG = new InheritableThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE   = new InheritableThreadLocal<>();
    /** The app_user.id of the authenticated user — used for ownership enforcement. */
    private static final ThreadLocal<Long>   USER_ID     = new InheritableThreadLocal<>();
    private static final ThreadLocal<Long> PROFILE_ID = new InheritableThreadLocal<>();

    private TenantContext() {}

    public static void set(Long schoolId, String schoolSlug, String role, Long userId, Long profileId) {
        SCHOOL_ID.set(schoolId);
        SCHOOL_SLUG.set(schoolSlug);
        USER_ROLE.set(role);
        USER_ID.set(userId);
        PROFILE_ID.set(profileId);
    }

    public static Long getSchoolId() {
        return SCHOOL_ID.get();
    }

    public static String getSchoolSlug() {
        return SCHOOL_SLUG.get();
    }

    public static String getUserRole() {
        return USER_ROLE.get();
    }

    public static Long getProfileId(){return PROFILE_ID.get();}

    /** Returns the authenticated user's app_user.id. */
    public static Long getUserId() {
        return USER_ID.get();
    }

    public static boolean isSystemAdmin() {
        return "SYSTEM_ADMIN".equals(USER_ROLE.get());
    }

    public static boolean isSchoolAdmin() {
        return "SCHOOL_ADMIN".equals(USER_ROLE.get());
    }

    public static boolean isTeacher() {
        return "TEACHER".equals(USER_ROLE.get());
    }

    public static boolean isStudent() {
        return "STUDENT".equals(USER_ROLE.get());
    }

    public static boolean hasSchool() {
        return SCHOOL_ID.get() != null;
    }

    /** MUST be called in a finally block after each request to prevent thread-pool leakage. */
    public static void clear() {
        SCHOOL_ID.remove();
        SCHOOL_SLUG.remove();
        USER_ROLE.remove();
        USER_ID.remove();
        PROFILE_ID.remove();
    }
}
