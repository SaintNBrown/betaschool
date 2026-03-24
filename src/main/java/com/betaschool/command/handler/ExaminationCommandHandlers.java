package com.betaschool.command.handler;

import com.betaschool.command.model.ExaminationCommand.*;
import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.entity.SchoolScoreConfigEntity.GradingBand;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.SchoolIdInjector;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantAccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ExaminationCommandHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateExaminationHandler implements CommandHandler<CreateExaminationCommand, Long> {

        private final JpaTermRepository termRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaExaminationRepository examinationRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;

        @Override
        public Long handle(CreateExaminationCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (examinationRepo.existsByTermIdAndClassSubjectIdAndSchoolId(
                    cmd.termId(), cmd.classSubjectId(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "An examination for this subject already exists in this term");
            }

            TermEntity term = termRepo.findByIdAndSchoolId(cmd.termId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Term", cmd.termId()));

            ClassSubjectEntity classSubject = classSubjectRepo
                    .findByIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", cmd.classSubjectId()));

            if (!classSubject.getClassSession().getId().equals(term.getClassSession().getId())) {
                throw new BusinessRuleViolationException(
                        "The subject does not belong to the same class-session as this term");
            }

            // ── Score weight validation ──────────────────────────────────────
            // Fall back to the school's configured CA:exam ratio, not hardcoded 40:60
            SchoolScoreConfigEntity config = scoreConfigRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());

            BigDecimal testMax = cmd.testMaxScore() != null
                    ? cmd.testMaxScore()
                    : BigDecimal.valueOf(config.getCaWeight());
            BigDecimal examMax = cmd.examMaxScore() != null
                    ? cmd.examMaxScore()
                    : BigDecimal.valueOf(config.getExamWeight());
            validateScoreWeights(testMax, examMax);

            // ── Exam time clash detection ────────────────────────────────────
            if (cmd.examDate() != null && cmd.examStartTime() != null && cmd.durationMinutes() != null) {
                detectTimeClash(cmd.termId(), schoolId, cmd.examStartTime(),
                        cmd.durationMinutes(), cmd.examDate(), -1L, examinationRepo);
            }

            ExaminationEntity examination = ExaminationEntity.builder()
                    .term(term)
                    .classSubject(classSubject)
                    .examDate(cmd.examDate())
                    .examStartTime(cmd.examStartTime())
                    .durationMinutes(cmd.durationMinutes())
                    .testMaxScore(testMax)
                    .examMaxScore(examMax)
                    .schoolId(schoolId)
                    .build();

            return examinationRepo.save(examination).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateExaminationHandler implements CommandHandler<UpdateExaminationCommand, Void> {

        private final JpaExaminationRepository examinationRepo;

        @Override
        public Void handle(UpdateExaminationCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ExaminationEntity exam = examinationRepo.findByIdAndSchoolId(cmd.examinationId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Examination", cmd.examinationId()));

            if (cmd.testMaxScore() != null || cmd.examMaxScore() != null) {
                BigDecimal testMax = cmd.testMaxScore() != null ? cmd.testMaxScore() : exam.getTestMaxScore();
                BigDecimal examMax = cmd.examMaxScore() != null ? cmd.examMaxScore() : exam.getExamMaxScore();
                validateScoreWeights(testMax, examMax);
                exam.setTestMaxScore(testMax);
                exam.setExamMaxScore(examMax);
            }

            if (cmd.examDate() != null) exam.setExamDate(cmd.examDate());
            if (cmd.examStartTime() != null) exam.setExamStartTime(cmd.examStartTime());
            if (cmd.durationMinutes() != null) exam.setDurationMinutes(cmd.durationMinutes());

            // Re-check clash after update if time fields are set
            if (exam.getExamDate() != null && exam.getExamStartTime() != null
                    && exam.getDurationMinutes() != null) {
                detectTimeClash(exam.getTerm().getId(), schoolId, exam.getExamStartTime(),
                        exam.getDurationMinutes(), exam.getExamDate(), exam.getId(), examinationRepo);
            }

            examinationRepo.save(exam);
            return null;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class RecordResultHandler implements CommandHandler<RecordResultCommand, Long> {

        private final JpaExaminationRepository examinationRepo;
        private final JpaStudentRepository studentRepo;
        private final JpaStudentSubjectEnrollmentRepository enrollmentRepo;
        private final JpaResultRepository resultRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;

        @Override
        public Long handle(RecordResultCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ExaminationEntity examination = examinationRepo
                    .findByIdAndSchoolId(cmd.examinationId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Examination", cmd.examinationId()));

            StudentEntity student = studentRepo.findByIdAndSchoolId(cmd.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", cmd.studentId()));

            if (!enrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                    cmd.studentId(), examination.getClassSubject().getId(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "Student is not enrolled in the subject being examined");
            }

            // Teacher can only submit results for their assigned subjects
            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null) throw new TenantAccessDeniedException(
                        "Your account has no linked teacher profile — please log out and log back in");
                if (!teacherSubjectAssignmentRepo.existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        profileId, examination.getClassSubject().getId(), schoolId)) {
                    throw new TenantAccessDeniedException(
                            "Access denied: you are not assigned to the subject for this examination");
                }
            }

            if (resultRepo.existsByExaminationIdAndStudentIdAndSchoolId(
                    cmd.examinationId(), cmd.studentId(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "Exam result already recorded for this student. Use the update endpoint.");
            }

            // Validate exam score does not exceed examMaxScore (exam component, not combined total)
            if (cmd.score() != null && cmd.score().compareTo(examination.getExamMaxScore()) > 0) {
                throw new BusinessRuleViolationException(
                        "Exam score " + cmd.score() + " exceeds examMaxScore of "
                        + examination.getExamMaxScore()
                        + ". Enter the exam component only (out of " + examination.getExamMaxScore()
                        + "). CA scores are recorded separately via /test-scores.");
            }

            SchoolScoreConfigEntity config = scoreConfigRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());

            // Include any already-recorded CA score so the grade is accurate immediately
            BigDecimal testScore = testScoreRepo
                    .findByExaminationIdAndStudentIdAndSchoolId(examination.getId(), cmd.studentId(), schoolId)
                    .map(TestScoreEntity::getScore)
                    .orElse(BigDecimal.ZERO);

            ResultEntity result = ResultEntity.builder()
                    .examination(examination)
                    .student(student)
                    .score(cmd.score())
                    .grade(computeGrade(cmd.score(), testScore, config))
                    .schoolId(schoolId)
                    .build();

            return resultRepo.save(result).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateResultHandler implements CommandHandler<UpdateResultCommand, Void> {

        private final JpaResultRepository resultRepo;
        private final JpaExaminationRepository examinationRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;

        @Override
        public Void handle(UpdateResultCommand cmd) {
            Long schoolId = SchoolIdInjector.require();
            ResultEntity result = resultRepo.findByIdAndSchoolId(cmd.resultId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Result", cmd.resultId()));

            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null) throw new TenantAccessDeniedException(
                        "Your account has no linked teacher profile — please log out and log back in");
                Long classSubjectId = result.getExamination().getClassSubject().getId();
                if (!teacherSubjectAssignmentRepo.existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        profileId, classSubjectId, schoolId)) {
                    throw new TenantAccessDeniedException(
                            "Access denied: you are not assigned to the subject for this result");
                }
            }

            ExaminationEntity exam = result.getExamination();
            if (cmd.score() != null && cmd.score().compareTo(exam.getExamMaxScore()) > 0) {
                throw new BusinessRuleViolationException(
                        "Exam score " + cmd.score() + " exceeds examMaxScore of " + exam.getExamMaxScore()
                        + ". Enter the exam component only (out of " + exam.getExamMaxScore() + ").");
            }

            SchoolScoreConfigEntity config = scoreConfigRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());

            // Fetch CA score so the grade reflects the true combined total
            BigDecimal testScore = testScoreRepo
                    .findByExaminationIdAndStudentIdAndSchoolId(
                            exam.getId(), result.getStudent().getId(), schoolId)
                    .map(TestScoreEntity::getScore)
                    .orElse(BigDecimal.ZERO);

            result.setScore(cmd.score());
            result.setGrade(computeGrade(cmd.score(), testScore, config));
            resultRepo.save(result);
            return null;
        }
    }

    /**
     * Atomic bulk result recording — all succeed or none commit.
     */
    @Component
    @RequiredArgsConstructor
    @Caching(evict = {
            @CacheEvict(value = "reportCards", allEntries = true),
            @CacheEvict(value = "transcripts", allEntries = true)
    })
    @Transactional
    public static class BulkRecordResultsHandler implements CommandHandler<BulkRecordResultsCommand, Void> {

        private final JpaExaminationRepository examinationRepo;
        private final JpaStudentRepository studentRepo;
        private final JpaStudentSubjectEnrollmentRepository enrollmentRepo;
        private final JpaResultRepository resultRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;
        private final JpaSchoolScoreConfigRepository scoreConfigRepo;

        @Override
        public Void handle(BulkRecordResultsCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ExaminationEntity examination = examinationRepo
                    .findByIdAndSchoolId(cmd.examinationId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Examination", cmd.examinationId()));

            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null) throw new TenantAccessDeniedException(
                        "Your account has no linked teacher profile — please log out and log back in");
                if (!teacherSubjectAssignmentRepo.existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        profileId, examination.getClassSubject().getId(), schoolId)) {
                    throw new TenantAccessDeniedException(
                            "Access denied: you are not assigned to the subject for this examination");
                }
            }

            SchoolScoreConfigEntity config = scoreConfigRepo.findBySchoolId(schoolId)
                    .orElseGet(() -> SchoolScoreConfigEntity.builder().schoolId(schoolId).build());

            BigDecimal examMax = BigDecimal.valueOf(config.getExamWeight());

            List<ResultEntity> toSave = new ArrayList<>();
            List<String> validationErrors = new ArrayList<>();

            for (BulkRecordResultsCommand.StudentScore ss : cmd.scores()) {
                StudentEntity student = studentRepo.findByIdAndSchoolId(ss.studentId(), schoolId).orElse(null);
                if (student == null) {
                    validationErrors.add("Student id=" + ss.studentId() + " not found"); continue; }
                if (!enrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                        ss.studentId(), examination.getClassSubject().getId(), schoolId)) {
                    validationErrors.add("Student id=" + ss.studentId() + " not enrolled in examined subject"); continue; }

                // Validate exam component score against examMaxScore
                if (ss.score() != null && ss.score().compareTo(examMax) > 0) {
                    validationErrors.add("Exam score " + ss.score() + " for student id=" + ss.studentId()
                            + " exceeds examMaxScore of " + examMax
                            + ". Enter the exam component only (out of " + examMax + "), "
                            + "CA scores are recorded separately via /test-scores.");
                    continue;
                }

                // Fetch any already-recorded CA/test score to compute an accurate grade
                BigDecimal testScore = testScoreRepo
                        .findByExaminationIdAndStudentIdAndSchoolId(
                                examination.getId(), ss.studentId(), schoolId)
                        .map(TestScoreEntity::getScore)
                        .orElse(BigDecimal.ZERO);

                String grade = computeGrade(ss.score(), testScore, config);

                // ── Upsert: update if resultId provided, create otherwise ──
                if (ss.resultId() != null) {
                    // Existing result — update in-place
                    resultRepo.findByIdAndSchoolId(ss.resultId(), schoolId).ifPresent(existing -> {
                        existing.setScore(ss.score());
                        existing.setGrade(grade);
                        resultRepo.save(existing);
                    });
                } else if (!resultRepo.existsByExaminationIdAndStudentIdAndSchoolId(
                        cmd.examinationId(), ss.studentId(), schoolId)) {
                    // No existing result — create new
                    toSave.add(ResultEntity.builder()
                            .examination(examination)
                            .student(student)
                            .score(ss.score())
                            .grade(grade)
                            .schoolId(schoolId)
                            .build());
                } else {
                    // resultId was not supplied but a record already exists — update by lookup
                    resultRepo.findByExaminationIdAndStudentIdAndSchoolId(
                            cmd.examinationId(), ss.studentId(), schoolId).ifPresent(existing -> {
                        existing.setScore(ss.score());
                        existing.setGrade(grade);
                        resultRepo.save(existing);
                    });
                }
            }

            if (!validationErrors.isEmpty()) {
                throw new BusinessRuleViolationException(
                        "Bulk result submission failed — no results saved. Errors: "
                                + String.join("; ", validationErrors));
            }
            resultRepo.saveAll(toSave);
            return null;
        }
    }

    // ── Test / CA Score Handlers ──────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Caching(evict = {
            @CacheEvict(value = "reportCards", allEntries = true),
            @CacheEvict(value = "transcripts", allEntries = true)
    })
    @Transactional
    public static class RecordTestScoreHandler implements CommandHandler<RecordTestScoreCommand, Long> {

        private final JpaExaminationRepository examinationRepo;
        private final JpaStudentRepository studentRepo;
        private final JpaStudentSubjectEnrollmentRepository enrollmentRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;

        @Override
        public Long handle(RecordTestScoreCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ExaminationEntity examination = examinationRepo
                    .findByIdAndSchoolId(cmd.examinationId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Examination", cmd.examinationId()));

            studentRepo.findByIdAndSchoolId(cmd.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", cmd.studentId()));

            if (!enrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                    cmd.studentId(), examination.getClassSubject().getId(), schoolId)) {
                throw new BusinessRuleViolationException("Student is not enrolled in the examined subject");
            }

            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null) throw new TenantAccessDeniedException(
                        "Your account has no linked teacher profile — please log out and log back in");
                if (!teacherSubjectAssignmentRepo.existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        profileId, examination.getClassSubject().getId(), schoolId)) {
                    throw new TenantAccessDeniedException(
                            "Access denied: you are not assigned to this subject");
                }
            }

            if (testScoreRepo.existsByExaminationIdAndStudentIdAndSchoolId(
                    cmd.examinationId(), cmd.studentId(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "Test score already recorded for this student. Use the update endpoint.");
            }

            if (cmd.score().compareTo(examination.getTestMaxScore()) > 0) {
                throw new BusinessRuleViolationException(
                        "Test score " + cmd.score() + " exceeds the maximum test score of "
                                + examination.getTestMaxScore());
            }

            // Load the student entity for the relationship
            StudentEntity student = studentRepo.findByIdAndSchoolId(cmd.studentId(), schoolId).get();

            TestScoreEntity ts = TestScoreEntity.builder()
                    .examination(examination)
                    .student(student)
                    .score(cmd.score())
                    .notes(cmd.notes())
                    .schoolId(schoolId)
                    .build();
            return testScoreRepo.save(ts).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateTestScoreHandler implements CommandHandler<UpdateTestScoreCommand, Void> {

        private final JpaTestScoreRepository testScoreRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;

        @Override
        public Void handle(UpdateTestScoreCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            TestScoreEntity ts = testScoreRepo.findById(cmd.testScoreId())
                    .filter(t -> t.getSchoolId().equals(schoolId))
                    .orElseThrow(() -> new ResourceNotFoundException("TestScore", cmd.testScoreId()));

            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null) throw new TenantAccessDeniedException(
                        "Your account has no linked teacher profile — please log out and log back in");
                if (!teacherSubjectAssignmentRepo.existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        profileId, ts.getExamination().getClassSubject().getId(), schoolId)) {
                    throw new TenantAccessDeniedException(
                            "Access denied: you are not assigned to this subject");
                }
            }

            BigDecimal max = ts.getExamination().getTestMaxScore();
            if (cmd.score().compareTo(max) > 0) {
                throw new BusinessRuleViolationException(
                        "Test score " + cmd.score() + " exceeds the maximum of " + max);
            }

            ts.setScore(cmd.score());
            if (cmd.notes() != null) ts.setNotes(cmd.notes());
            testScoreRepo.save(ts);
            return null;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class BulkRecordTestScoresHandler
            implements CommandHandler<BulkRecordTestScoresCommand, Void> {

        private final JpaExaminationRepository examinationRepo;
        private final JpaStudentRepository studentRepo;
        private final JpaStudentSubjectEnrollmentRepository enrollmentRepo;
        private final JpaTestScoreRepository testScoreRepo;
        private final JpaTeacherSubjectAssignmentRepository teacherSubjectAssignmentRepo;

        @Override
        public Void handle(BulkRecordTestScoresCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ExaminationEntity examination = examinationRepo
                    .findByIdAndSchoolId(cmd.examinationId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Examination", cmd.examinationId()));

            if (TenantContext.isTeacher()) {
                Long profileId = TenantContext.getProfileId();
                if (profileId == null) throw new TenantAccessDeniedException(
                        "Your account has no linked teacher profile — please log out and log back in");
                if (!teacherSubjectAssignmentRepo.existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        profileId, examination.getClassSubject().getId(), schoolId)) {
                    throw new TenantAccessDeniedException(
                            "Access denied: you are not assigned to this subject");
                }
            }

            List<TestScoreEntity> toSave = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (BulkRecordTestScoresCommand.StudentTestScore ss : cmd.scores()) {
                StudentEntity student = studentRepo.findByIdAndSchoolId(ss.studentId(), schoolId).orElse(null);
                if (student == null) { errors.add("Student id=" + ss.studentId() + " not found"); continue; }
                if (!enrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                        ss.studentId(), examination.getClassSubject().getId(), schoolId)) {
                    errors.add("Student id=" + ss.studentId() + " not enrolled"); continue; }
                if (testScoreRepo.existsByExaminationIdAndStudentIdAndSchoolId(
                        cmd.examinationId(), ss.studentId(), schoolId)) {
                    errors.add("Test score already exists for student id=" + ss.studentId()); continue; }
                if (ss.score() != null && ss.score().compareTo(examination.getTestMaxScore()) > 0) {
                    errors.add("Score " + ss.score() + " for student id=" + ss.studentId()
                            + " exceeds test max " + examination.getTestMaxScore()); continue; }

                toSave.add(TestScoreEntity.builder()
                        .examination(examination).student(student)
                        .score(ss.score()).notes(ss.notes()).schoolId(schoolId).build());
            }

            if (!errors.isEmpty()) throw new BusinessRuleViolationException(
                    "Bulk test score submission failed: " + String.join("; ", errors));
            testScoreRepo.saveAll(toSave);
            return null;
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    /**
     * Detects time-window clashes among exams scheduled on the same date within
     * the same term. Two exams clash if their time windows overlap.
     * Clash check is done in Java to avoid non-portable JPQL time arithmetic.
     */
    static void detectTimeClash(Long termId, Long schoolId, LocalTime startTime,
                                 int durationMinutes, java.time.LocalDate examDate,
                                 Long excludeExaminationId,
                                 JpaExaminationRepository examinationRepo) {
        LocalTime proposedEnd = startTime.plusMinutes(durationMinutes);
        List<ExaminationEntity> sameDay = examinationRepo
                .findByTermIdAndSchoolIdAndExamDate(termId, schoolId, examDate);

        for (ExaminationEntity existing : sameDay) {
            if (existing.getId().equals(excludeExaminationId)) continue;
            LocalTime existingStart = existing.getExamStartTime();
            LocalTime existingEnd   = existingStart.plusMinutes(existing.getDurationMinutes());
            // Overlap: startA < endB AND startB < endA
            if (startTime.isBefore(existingEnd) && existingStart.isBefore(proposedEnd)) {
                throw new BusinessRuleViolationException(
                        "Exam time clash: proposed "
                        + startTime + "-" + proposedEnd
                        + " overlaps with existing exam for '"
                        + existing.getClassSubject().getSubject().getName()
                        + "' at " + existingStart + "-" + existingEnd
                        + " on " + examDate);
            }
        }
    }

    static void validateScoreWeights(BigDecimal testMax, BigDecimal examMax) {
        if (testMax == null || examMax == null) return;
        if (testMax.compareTo(BigDecimal.ZERO) < 0 || examMax.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleViolationException("Score weights must be non-negative");
        }
        if (testMax.add(examMax).compareTo(BigDecimal.valueOf(100)) != 0) {
            throw new BusinessRuleViolationException(
                    "testMaxScore (" + testMax + ") + examMaxScore (" + examMax + ") must equal 100");
        }
    }

    /**
     * Computes the grade letter for a combined (exam + CA) score using the
     * school's configured grading bands.  Falls back to the system defaults
     * if the school has not yet saved a custom config.
     *
     * @param examScore  the exam component score (may be null → treated as 0)
     * @param testScore  the CA/test component score (may be null → treated as 0)
     * @param config     the school's score configuration (never null; use defaults if not persisted)
     */
    static String computeGrade(BigDecimal examScore, BigDecimal testScore,
                                SchoolScoreConfigEntity config) {
        if (examScore == null) return null;
        BigDecimal combined = examScore.add(testScore != null ? testScore : BigDecimal.ZERO);
        return config.resolveGrade(combined.intValue());
    }
}