package com.betaschool.query.model;

import java.math.BigDecimal;
import java.util.List;

public sealed interface StudentQueryResult {

    record StudentSummary(
            Long id,
            String surname,
            String otherNames,
            String email,
            String fullName
    ) implements StudentQueryResult {}

    record StudentDetail(
            Long id,
            String surname,
            String otherNames,
            String email
    ) implements StudentQueryResult {}

    record StudentSubjectItem(
            Long classSubjectId,
            Long subjectId,
            String subjectName,
            boolean isElective,
            String teacherName
    ) implements StudentQueryResult {}

    record ReportCard(
            Long studentId,
            String studentName,
            String className,
            String sessionName,
            Integer termNumber,
            List<ReportCardEntry> entries,
            BigDecimal totalScore,
            BigDecimal averageScore,        // average of subject combined scores for this term
            Integer position,               // class rank (null when showPosition = false)
            String termGrade,               // grade derived from averageScore % vs school grading bands
            boolean showPosition            // true → show position; false → show termGrade only
    ) implements StudentQueryResult {}

    record ReportCardEntry(
            String subjectName,
            String teacherName,
            BigDecimal testScore,          // CA / test component (out of testMaxScore)
            BigDecimal examScore,          // exam component (out of examMaxScore)
            BigDecimal combinedScore,      // testScore + examScore (out of 100)
            BigDecimal testMaxScore,       // max marks for test component
            BigDecimal examMaxScore,       // max marks for exam component
            String grade
    ) implements StudentQueryResult {}

    record FullTranscript(
            Long studentId,
            String studentName,
            String sessionName,
            List<TermTranscript> terms,
            BigDecimal sessionAveragePercentage   // average of all term averages across the session
    ) implements StudentQueryResult {}

    record TermTranscript(
            Integer termNumber,
            List<ReportCardEntry> entries,
            BigDecimal averageScore
    ) implements StudentQueryResult {}
}
