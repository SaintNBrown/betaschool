package com.betaschool.query.model;

public sealed interface TeacherQueryResult {
    record TeacherSummary(
            Long id,
            String surname,
            String otherNames,
            String email,
            String accountStatus,   // "ACTIVE", "INACTIVE", "SUSPENDED", or "NO_ACCOUNT"
            Long userId             // AppUser.id — needed for deactivate/reactivate calls
    ) implements TeacherQueryResult {}
    record TeacherDetail(Long id, String surname, String otherNames, String email) implements TeacherQueryResult {}
    record TeacherClassItem(Long classSessionId, String className, String sessionName, boolean isFormTeacher) implements TeacherQueryResult {}
    record TeacherSubjectItem(Long classSubjectId, String subjectName, String className, String sessionName, boolean isElective) implements TeacherQueryResult {}
}
