package com.betaschool.query.model;

import com.betaschool.shared.Query;
import org.springframework.data.domain.Pageable;

public sealed interface StudentQuery {

    record GetStudentByIdQuery(Long studentId) implements Query<StudentQueryResult.StudentDetail>, StudentQuery {}

    record GetStudentsByClassSessionQuery(
            Long classSessionId
    ) implements Query<java.util.List<StudentQueryResult.StudentSummary>>, StudentQuery {}

    /** Returns only students enrolled in a specific class-subject (for result entry). */
    record GetStudentsByClassSubjectQuery(
            Long classSubjectId
    ) implements Query<java.util.List<StudentQueryResult.StudentSummary>>, StudentQuery {}

    record SearchStudentsQuery(
            String search,
            Pageable pageable
    ) implements Query<org.springframework.data.domain.Page<StudentQueryResult.StudentSummary>>, StudentQuery {}

    record GetStudentSubjectsQuery(
            Long studentId,
            Long classSessionId
    ) implements Query<java.util.List<StudentQueryResult.StudentSubjectItem>>, StudentQuery {}

    record GetStudentReportCardQuery(
            Long studentId,
            Long termId
    ) implements Query<StudentQueryResult.ReportCard>, StudentQuery {}

    record GetStudentFullTranscriptQuery(
            Long studentId,
            Long sessionId
    ) implements Query<StudentQueryResult.FullTranscript>, StudentQuery {}
}
