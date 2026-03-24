package com.betaschool.query.model;

import com.betaschool.shared.Query;

import java.util.List;

public sealed interface AcademicQuery {

    record GetAllSessionsQuery() implements Query<List<AcademicQueryResult.SessionSummary>>, AcademicQuery {}

    record GetSessionByIdQuery(Long sessionId) implements Query<AcademicQueryResult.SessionDetail>, AcademicQuery {}

    /**
     * Issue 5: Returns the session designated as current for the authenticated school.
     */
    record GetCurrentSessionQuery() implements Query<AcademicQueryResult.SessionSummary>, AcademicQuery {}

    record GetAllClassesQuery() implements Query<List<AcademicQueryResult.ClassSummary>>, AcademicQuery {}

    record GetClassSessionsQuery(Long sessionId) implements Query<List<AcademicQueryResult.ClassSessionSummary>>, AcademicQuery {}

    record GetClassSubjectsQuery(Long classSessionId) implements Query<List<AcademicQueryResult.ClassSubjectItem>>, AcademicQuery {}

    /**
     * Returns only elective subjects for a class-session.
     * Used by the frontend when showing the list of subjects a student can opt into.
     */
    record GetElectiveSubjectsQuery(Long classSessionId) implements Query<List<AcademicQueryResult.ClassSubjectItem>>, AcademicQuery {}

    record GetAllSubjectsQuery() implements Query<List<AcademicQueryResult.SubjectSummary>>, AcademicQuery {}

    record GetTermsByClassSessionQuery(Long classSessionId) implements Query<List<AcademicQueryResult.TermSummary>>, AcademicQuery {}

    record GetExaminationsByTermQuery(Long termId) implements Query<List<AcademicQueryResult.ExaminationSummary>>, AcademicQuery {}

    /**
     * Returns already-recorded exam scores and CA scores for a given examination,
     * keyed by studentId. Used to pre-populate result entry modals.
     */
    record GetExistingScoresQuery(Long examinationId) implements Query<AcademicQueryResult.ExistingScores>, AcademicQuery {}

}
