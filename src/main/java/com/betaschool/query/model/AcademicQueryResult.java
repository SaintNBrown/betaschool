package com.betaschool.query.model;

import java.time.LocalDate;
import java.util.List;

public sealed interface AcademicQueryResult {

    record SessionSummary(Long id, String sessionName, LocalDate startDate, LocalDate closingDate, boolean isCurrent)
            implements AcademicQueryResult {}

    record SessionDetail(Long id, String sessionName, LocalDate startDate, LocalDate closingDate,
                         List<ClassSessionSummary> classes) implements AcademicQueryResult {}

    record ClassSummary(Long id, String name) implements AcademicQueryResult {}

    record ClassSessionSummary(Long id, Long classId, String className, Long sessionId, String sessionName)
            implements AcademicQueryResult {}

    record ClassSubjectItem(Long id, Long subjectId, String subjectName, boolean isElective,
                            Long teacherId, String teacherName) implements AcademicQueryResult {}

    record SubjectSummary(Long id, String name) implements AcademicQueryResult {}

    record TermSummary(Long id, Integer termNumber, Long classSessionId, String className, String sessionName)
            implements AcademicQueryResult {}

    record ExaminationSummary(Long id, Long termId, Integer termNumber, Long classSubjectId,
                               String subjectName, String teacherName, LocalDate examDate)
            implements AcademicQueryResult {}


    /**
     * Pre-existing scores for an examination, keyed by studentId.
     * Returned when opening the result entry modal so already-recorded scores
     * are shown immediately without the teacher having to look them up.
     *
     * examScores  — map of studentId → exam score (null if not yet recorded)
     * testScores  — map of studentId → CA/test score (null if not yet recorded)
     * resultIds   — map of studentId → resultId (for update vs create decisions)
     * testScoreIds — map of studentId → testScoreId
     */
    record ExistingScores(
            java.util.Map<Long, java.math.BigDecimal> examScores,
            java.util.Map<Long, java.math.BigDecimal> testScores,
            java.util.Map<Long, Long> resultIds,
            java.util.Map<Long, Long> testScoreIds
    ) implements AcademicQueryResult {}
}
