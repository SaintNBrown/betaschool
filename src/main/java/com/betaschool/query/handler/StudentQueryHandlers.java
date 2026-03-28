package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.query.model.StudentQuery.*;
import com.betaschool.query.model.StudentQueryResult.*;
import com.betaschool.shared.QueryHandler;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class StudentQueryHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetStudentByIdHandler implements QueryHandler<GetStudentByIdQuery, StudentDetail> {

        private final JpaStudentRepository studentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public StudentDetail handle(GetStudentByIdQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            if (TenantContext.isStudent()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.studentId(), UserProfileEntity.ProfileType.STUDENT);
            }
            return studentRepo.findByIdAndSchoolId(query.studentId(), schoolId)
                    .map(s -> new StudentDetail(s.getId(), s.getSurname(), s.getOtherNames(), s.getEmail()))
                    .orElseThrow(() -> new ResourceNotFoundException("Student", query.studentId()));
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class SearchStudentsHandler implements QueryHandler<SearchStudentsQuery, Page<StudentSummary>> {

        private final JpaStudentRepository studentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Page<StudentSummary> handle(SearchStudentsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            tenantGuard.requireRole("SCHOOL_ADMIN", "TEACHER", "SYSTEM_ADMIN");
            return studentRepo.searchByNameAndSchoolId(query.search(), schoolId, query.pageable())
                    .map(s -> new StudentSummary(s.getId(), s.getSurname(), s.getOtherNames(), s.getEmail(),
                            s.getSurname() + " " + s.getOtherNames()));
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetStudentsByClassSessionHandler
            implements QueryHandler<GetStudentsByClassSessionQuery, List<StudentSummary>> {

        private final JpaStudentClassEnrollmentRepository enrollmentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<StudentSummary> handle(GetStudentsByClassSessionQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            tenantGuard.requireRole("SCHOOL_ADMIN", "TEACHER", "SYSTEM_ADMIN");
            return enrollmentRepo.findByClassSessionIdAndSchoolId(query.classSessionId(), schoolId).stream()
                    .map(e -> {
                        StudentEntity s = e.getStudent();
                        return new StudentSummary(s.getId(), s.getSurname(), s.getOtherNames(), s.getEmail(),
                                s.getSurname() + " " + s.getOtherNames());
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * Returns only students enrolled in a specific class-subject.
     * Used by result-entry screens so teachers/admins only see students
     * who are actually enrolled in that subject, not all students in the class.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetStudentsByClassSubjectHandler
            implements QueryHandler<GetStudentsByClassSubjectQuery, List<StudentSummary>> {

        private final JpaStudentSubjectEnrollmentRepository subjectEnrollmentRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<StudentSummary> handle(GetStudentsByClassSubjectQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            // Verify the class-subject belongs to this school
            classSubjectRepo.findByIdAndSchoolId(query.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ClassSubject", query.classSubjectId()));

            // Teachers may only retrieve the student list for subjects they are assigned to
            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null || !teacherSubjectAssignmentRepo
                        .existsByTeacherIdAndClassSubjectIdAndSchoolId(
                                profileId, query.classSubjectId(), schoolId)) {
                    throw new com.betaschool.tenant.context.TenantAccessDeniedException(
                            "Access denied: you are not assigned to this subject");
                }
            }

            return subjectEnrollmentRepo
                    .findByClassSubjectIdAndSchoolId(query.classSubjectId(), schoolId)
                    .stream()
                    .map(e -> {
                        StudentEntity s = e.getStudent();
                        return new StudentSummary(s.getId(), s.getSurname(), s.getOtherNames(),
                                s.getEmail(), s.getSurname() + " " + s.getOtherNames());
                    })
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetStudentSubjectsHandler
            implements QueryHandler<GetStudentSubjectsQuery, List<StudentSubjectItem>> {

        private final JpaStudentSubjectEnrollmentRepository enrollmentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<StudentSubjectItem> handle(GetStudentSubjectsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            if (TenantContext.isStudent()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.studentId(), UserProfileEntity.ProfileType.STUDENT);
            }
            return enrollmentRepo
                    .findByStudentIdAndClassSessionIdAndSchoolId(
                            query.studentId(), query.classSessionId(), schoolId)
                    .stream()
                    .map(e -> {
                        ClassSubjectEntity cs = e.getClassSubject();
                        String teacherName = cs.getTeacherAssignment() != null
                                ? cs.getTeacherAssignment().getTeacher().getSurname() + " "
                                  + cs.getTeacherAssignment().getTeacher().getOtherNames()
                                : "Unassigned";
                        return new StudentSubjectItem(cs.getId(), cs.getSubject().getId(),
                                cs.getSubject().getName(), cs.isElective(), teacherName);
                    })
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetStudentReportCardHandler
            implements QueryHandler<GetStudentReportCardQuery, ReportCard> {

        private final JpaResultRepository resultRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaStudentRepository studentRepo;
        private final JpaTermRepository termRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;
        private final TenantGuard tenantGuard;

        @Override
        @Cacheable(value = "reportCards",
                key = "#query.studentId() + '-' + #query.termId() + '-' + T(com.betaschool.tenant.context.TenantContext).getSchoolId()")
        public ReportCard handle(GetStudentReportCardQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            if (TenantContext.isStudent()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.studentId(), UserProfileEntity.ProfileType.STUDENT);
            }

            StudentEntity student = studentRepo.findByIdAndSchoolId(query.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", query.studentId()));

            TermEntity term = termRepo.findByIdAndSchoolId(query.termId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Term", query.termId()));

            List<ResultEntity> results = resultRepo.findReportCard(query.studentId(), query.termId(), schoolId);

            SchoolScoreConfigEntity config = scoreConfigRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());

            List<ReportCardEntry> entries = results.stream().map(r -> {
                ClassSubjectEntity cs = r.getExamination().getClassSubject();
                String teacherName = cs.getTeacherAssignment() != null
                        ? cs.getTeacherAssignment().getTeacher().getSurname() + " "
                          + cs.getTeacherAssignment().getTeacher().getOtherNames()
                        : "Unassigned";

                // Fetch the test score for this student+examination
                BigDecimal testScore = testScoreRepo
                        .findByExaminationIdAndStudentIdAndSchoolId(
                                r.getExamination().getId(), query.studentId(), schoolId)
                        .map(ts -> ts.getScore())
                        .orElse(null);

                BigDecimal examScore = r.getScore();
                BigDecimal combined = (examScore != null ? examScore : BigDecimal.ZERO)
                        .add(testScore != null ? testScore : BigDecimal.ZERO);

                // Resolve grade live from the school's current grading bands
                String grade = combined.compareTo(BigDecimal.ZERO) > 0
                        ? config.resolveGrade(combined.intValue())
                        : r.getGrade();

                return new ReportCardEntry(
                        cs.getSubject().getName(), teacherName,
                        testScore, examScore, combined,
                        r.getExamination().getTestMaxScore(),
                        r.getExamination().getExamMaxScore(),
                        grade);
            }).collect(Collectors.toList());

            List<ReportCardEntry> scoredEntries = entries.stream()
                    .filter(e -> e.combinedScore() != null && e.combinedScore().compareTo(BigDecimal.ZERO) > 0)
                    .collect(Collectors.toList());

            BigDecimal total = scoredEntries.stream()
                    .map(ReportCardEntry::combinedScore)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal average = scoredEntries.isEmpty() ? BigDecimal.ZERO
                    : total.divide(BigDecimal.valueOf(scoredEntries.size()), 2, RoundingMode.HALF_UP);

            // Term grade: resolve the student's average score (already out of 100
            // because each subject's combined score sums to 100) against the
            // school's grading bands. e.g. average 98% → "A".
            String termGrade = config.resolveGrade(average.intValue());

            Long classSessionId = term.getClassSession().getId();
            boolean showPosition = config.isShowStudentPosition();

            // Only compute the class position when the school has opted to show it.
            // Skip the extra DB query when showPosition = false.
            Integer position = showPosition
                    ? computePosition(query.studentId(), classSessionId, query.termId(), schoolId, total)
                    : null;

            return new ReportCard(student.getId(),
                    student.getSurname() + " " + student.getOtherNames(),
                    term.getClassSession().getClazz().getName(),
                    term.getSession().getSessionName(),
                    term.getTermNumber(), entries, total, average,
                    position, termGrade, showPosition);
        }

        private Integer computePosition(Long studentId, Long classSessionId, Long termId,
                                        Long schoolId, BigDecimal studentTotal) {
            List<Object[]> classTotals = resultRepo.findStudentTotalsForTermAndClassSession(
                    termId, classSessionId, schoolId);
            List<BigDecimal> sortedTotals = classTotals.stream()
                    .map(row -> {
                        if (row[1] == null) return BigDecimal.ZERO;
                        // The combined-total expression in JPQL may return Double or BigDecimal
                        // depending on the driver — normalise to BigDecimal safely.
                        if (row[1] instanceof BigDecimal bd) return bd;
                        return BigDecimal.valueOf(((Number) row[1]).doubleValue());
                    })
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            long higherCount = sortedTotals.stream()
                    .filter(t -> t.compareTo(studentTotal) > 0)
                    .count();
            return (int) higherCount + 1;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetStudentFullTranscriptHandler
            implements QueryHandler<GetStudentFullTranscriptQuery, FullTranscript> {

        private final JpaResultRepository resultRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaStudentRepository studentRepo;
        private final JpaSessionRepository sessionRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;
        private final TenantGuard tenantGuard;

        @Override
        @Cacheable(value = "transcripts",
                key = "#query.studentId() + '-' + #query.sessionId() + '-' + T(com.betaschool.tenant.context.TenantContext).getSchoolId()")
        public FullTranscript handle(GetStudentFullTranscriptQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            if (TenantContext.isStudent()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.studentId(), UserProfileEntity.ProfileType.STUDENT);
            }

            StudentEntity student = studentRepo.findByIdAndSchoolId(query.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", query.studentId()));
            sessionRepo.findByIdAndSchoolId(query.sessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", query.sessionId()));

            List<ResultEntity> all = resultRepo.findByStudentIdAndSessionIdAndSchoolId(
                    query.studentId(), query.sessionId(), schoolId);

            SchoolScoreConfigEntity config = scoreConfigRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());

            Map<Integer, List<ResultEntity>> byTerm = all.stream()
                    .collect(Collectors.groupingBy(r -> r.getExamination().getTerm().getTermNumber()));

            List<TermTranscript> terms = byTerm.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> {
                        List<ReportCardEntry> entries = entry.getValue().stream().map(r -> {
                            ClassSubjectEntity cs = r.getExamination().getClassSubject();
                            String teacher = cs.getTeacherAssignment() != null
                                    ? cs.getTeacherAssignment().getTeacher().getSurname()
                                      + " " + cs.getTeacherAssignment().getTeacher().getOtherNames()
                                    : "Unassigned";
                            BigDecimal testScore = testScoreRepo
                                    .findByExaminationIdAndStudentIdAndSchoolId(
                                            r.getExamination().getId(), query.studentId(), schoolId)
                                    .map(ts -> ts.getScore()).orElse(null);
                            BigDecimal examScore = r.getScore();
                            BigDecimal combined = (examScore != null ? examScore : BigDecimal.ZERO)
                                    .add(testScore != null ? testScore : BigDecimal.ZERO);
                            String grade = combined.compareTo(BigDecimal.ZERO) > 0
                                    ? config.resolveGrade(combined.intValue())
                                    : r.getGrade();
                            return new ReportCardEntry(cs.getSubject().getName(), teacher,
                                    testScore, examScore, combined,
                                    r.getExamination().getTestMaxScore(),
                                    r.getExamination().getExamMaxScore(),
                                    grade);
                        }).collect(Collectors.toList());

                        List<ReportCardEntry> scored = entries.stream()
                                .filter(e -> e.combinedScore() != null
                                        && e.combinedScore().compareTo(BigDecimal.ZERO) > 0)
                                .collect(Collectors.toList());
                        BigDecimal avg = scored.isEmpty() ? BigDecimal.ZERO
                                : scored.stream().map(ReportCardEntry::combinedScore)
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .divide(BigDecimal.valueOf(scored.size()), 2, RoundingMode.HALF_UP);

                        return new TermTranscript(entry.getKey(), entries, avg);
                    })
                    .collect(Collectors.toList());

            // Session average: mean of all term averages
            BigDecimal sessionAvg = terms.isEmpty() ? BigDecimal.ZERO
                    : terms.stream()
                        .map(TermTranscript::averageScore)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(terms.size()), 2, RoundingMode.HALF_UP);

            String sessionName = all.isEmpty() ? ""
                    : all.get(0).getExamination().getTerm().getSession().getSessionName();

            return new FullTranscript(student.getId(),
                    student.getSurname() + " " + student.getOtherNames(),
                    sessionName, terms, sessionAvg);
        }
    }
}
