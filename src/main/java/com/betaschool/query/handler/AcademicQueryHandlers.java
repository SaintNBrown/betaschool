package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.query.model.AcademicQuery.*;
import com.betaschool.query.model.AcademicQueryResult.*;
import com.betaschool.shared.QueryHandler;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AcademicQueryHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetAllSessionsHandler implements QueryHandler<GetAllSessionsQuery, List<SessionSummary>> {

        private final JpaSessionRepository sessionRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<SessionSummary> handle(GetAllSessionsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return sessionRepo.findBySchoolId(schoolId).stream()
                    .map(s -> new SessionSummary(s.getId(), s.getSessionName(),
                            s.getStartDate(), s.getClosingDate(), s.isCurrent()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Issue 5: Returns the session currently designated as active for the school.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetCurrentSessionHandler implements QueryHandler<GetCurrentSessionQuery, SessionSummary> {

        private final JpaSessionRepository sessionRepo;
        private final TenantGuard tenantGuard;

        @Override
        public SessionSummary handle(GetCurrentSessionQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return sessionRepo.findBySchoolIdAndCurrentTrue(schoolId)
                    .map(s -> new SessionSummary(s.getId(), s.getSessionName(),
                            s.getStartDate(), s.getClosingDate(), s.isCurrent()))
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No current session has been set. Ask your school admin to set one."));
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetSessionByIdHandler implements QueryHandler<GetSessionByIdQuery, SessionDetail> {

        private final JpaSessionRepository sessionRepo;
        private final JpaClassSessionRepository classSessionRepo;
        private final TenantGuard tenantGuard;

        @Override
        public SessionDetail handle(GetSessionByIdQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            SessionEntity session = sessionRepo.findByIdAndSchoolId(query.sessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", query.sessionId()));

            List<ClassSessionSummary> classes = classSessionRepo
                    .findBySessionIdAndSchoolIdWithDetails(query.sessionId(), schoolId).stream()
                    .map(cs -> new ClassSessionSummary(cs.getId(), cs.getClazz().getId(),
                            cs.getClazz().getName(), cs.getSession().getId(), cs.getSession().getSessionName()))
                    .collect(Collectors.toList());

            return new SessionDetail(session.getId(), session.getSessionName(),
                    session.getStartDate(), session.getClosingDate(), classes);
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetAllClassesHandler implements QueryHandler<GetAllClassesQuery, List<ClassSummary>> {

        private final JpaClassRepository classRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<ClassSummary> handle(GetAllClassesQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return classRepo.findBySchoolId(schoolId).stream()
                    .map(c -> new ClassSummary(c.getId(), c.getName()))
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetClassSubjectsHandler
            implements QueryHandler<GetClassSubjectsQuery, List<ClassSubjectItem>> {

        private final JpaClassSubjectRepository classSubjectRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<ClassSubjectItem> handle(GetClassSubjectsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return classSubjectRepo.findByClassSessionIdAndSchoolId(query.classSessionId(), schoolId).stream()
                    .map(cs -> toClassSubjectItem(cs))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Returns only elective subjects for a class-session.
     * Used by the frontend to populate the elective enrollment list — compulsory
     * subjects are excluded because they cannot be manually enrolled into.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetElectiveSubjectsHandler
            implements QueryHandler<GetElectiveSubjectsQuery, List<ClassSubjectItem>> {

        private final JpaClassSubjectRepository classSubjectRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<ClassSubjectItem> handle(GetElectiveSubjectsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return classSubjectRepo.findElectiveByClassSessionIdAndSchoolId(query.classSessionId(), schoolId).stream()
                    .map(cs -> toClassSubjectItem(cs))
                    .collect(Collectors.toList());
        }
    }

    private static ClassSubjectItem toClassSubjectItem(
            ClassSubjectEntity cs) {
        Long teacherId = null;
        String teacherName = "Unassigned";
        if (cs.getTeacherAssignment() != null) {
            var t = cs.getTeacherAssignment().getTeacher();
            teacherId = t.getId();
            teacherName = t.getSurname() + " " + t.getOtherNames();
        }
        return new ClassSubjectItem(cs.getId(), cs.getSubject().getId(),
                cs.getSubject().getName(), cs.isElective(), teacherId, teacherName);
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetAllSubjectsHandler implements QueryHandler<GetAllSubjectsQuery, List<SubjectSummary>> {

        private final JpaSubjectRepository subjectRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<SubjectSummary> handle(GetAllSubjectsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return subjectRepo.findBySchoolId(schoolId).stream()
                    .map(s -> new SubjectSummary(s.getId(), s.getName()))
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTermsByClassSessionHandler
            implements QueryHandler<GetTermsByClassSessionQuery, List<TermSummary>> {

        private final JpaTermRepository termRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TermSummary> handle(GetTermsByClassSessionQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            return termRepo.findByClassSessionIdAndSchoolId(query.classSessionId(), schoolId).stream()
                    .map(t -> new TermSummary(t.getId(), t.getTermNumber(),
                            t.getClassSession().getId(),
                            t.getClassSession().getClazz().getName(),
                            t.getSession().getSessionName()))
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetExaminationsByTermHandler
            implements QueryHandler<GetExaminationsByTermQuery, List<ExaminationSummary>> {

        private final JpaExaminationRepository examinationRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<ExaminationSummary> handle(GetExaminationsByTermQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            List<ExaminationEntity> exams = examinationRepo
                    .findByTermIdAndSchoolIdWithSubjectDetails(query.termId(), schoolId);

            // Teachers may only see examinations for subjects they are assigned to.
            // This prevents a teacher from entering scores for another teacher's subject.
            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId != null) {
                    // Collect the classSubject IDs this teacher owns
                    java.util.Set<Long> assignedSubjectIds = teacherSubjectAssignmentRepo
                            .findByTeacherIdAndSchoolId(profileId, schoolId)
                            .stream()
                            .map(a -> a.getClassSubject().getId())
                            .collect(Collectors.toSet());

                    exams = exams.stream()
                            .filter(e -> assignedSubjectIds.contains(e.getClassSubject().getId()))
                            .collect(Collectors.toList());
                }
            }

            return exams.stream()
                    .map(e -> {
                        ClassSubjectEntity cs = e.getClassSubject();
                        String teacherName = cs.getTeacherAssignment() != null
                                ? cs.getTeacherAssignment().getTeacher().getSurname()
                                  + " " + cs.getTeacherAssignment().getTeacher().getOtherNames()
                                : "Unassigned";
                        return new ExaminationSummary(e.getId(), e.getTerm().getId(),
                                e.getTerm().getTermNumber(), cs.getId(), cs.getSubject().getName(),
                                teacherName, e.getExamDate());
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * Fetches all already-recorded exam scores and CA/test scores for a given examination.
     * Returns maps keyed by studentId so the frontend can pre-populate entry modals.
     * Teachers are limited to examinations for their assigned subjects (enforced by
     * the exam-list filter in GetExaminationsByTermHandler; we still guard here).
     */
    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetExistingScoresHandler
            implements QueryHandler<GetExistingScoresQuery, ExistingScores> {

        private final JpaExaminationRepository examinationRepo;
        private final JpaResultRepository resultRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final TenantGuard tenantGuard;

        @Override
        public ExistingScores handle(GetExistingScoresQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            // Verify the examination exists and belongs to this school
            examinationRepo.findByIdAndSchoolId(query.examinationId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Examination", query.examinationId()));

            // Exam scores: studentId → score
            Map<Long, BigDecimal> examScores = new HashMap<>();
            Map<Long, Long>       resultIds  = new HashMap<>();
            resultRepo.findAllByExaminationIdAndSchoolId(query.examinationId(), schoolId)
                    .forEach(r -> {
                        examScores.put(r.getStudent().getId(), r.getScore());
                        resultIds.put(r.getStudent().getId(), r.getId());
                    });

            // CA/test scores: studentId → score
            Map<Long, BigDecimal> testScores   = new HashMap<>();
            Map<Long, Long>       testScoreIds = new HashMap<>();
            testScoreRepo.findAllByExaminationIdAndSchoolId(query.examinationId(), schoolId)
                    .forEach(ts -> {
                        testScores.put(ts.getStudent().getId(), ts.getScore());
                        testScoreIds.put(ts.getStudent().getId(), ts.getId());
                    });

            return new ExistingScores(examScores, testScores, resultIds, testScoreIds);
        }
    }
}

