package com.betaschool.infrastructure.excel;

import com.betaschool.infrastructure.persistence.entity.ExaminationEntity;
import com.betaschool.infrastructure.persistence.entity.StudentEntity;
import com.betaschool.infrastructure.persistence.entity.TestScoreEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity.ProfileType;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.infrastructure.persistence.repository.auth.JpaUserProfileRepository;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Imports CA (continuous assessment) scores from a multi-component .xlsx file
 * where each CA component has its own raw maximum specified in the header.
 *
 * Resolution formula:
 *   rawTotal    = sum of all per-component raw scores in the row
 *   rawMaxTotal = sum of all per-component maxima (from headers)
 *   storedScore = round((rawTotal / rawMaxTotal) × configuredCaMax, 2)
 *
 * This means a school running CA1/10 + CA2/20 + CA3/10 (total 40) maps
 * proportionally to whatever the examination's testMaxScore is set to.
 *
 * Teacher access: when the caller is a TEACHER, their profileId is checked
 * against the classSubject's teacher_subject_assignment. Teachers can only
 * import scores for subjects they are assigned to.
 *
 * Exam score import (flat Student Email | Score) is handled by the
 * existing ScoreImportService — no duplication needed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaScoreImportService {

    private final JpaExaminationRepository            examinationRepo;
    private final JpaStudentRepository                studentRepo;
    private final JpaTestScoreRepository              testScoreRepo;
    private final JpaTeacherSubjectAssignmentRepository teacherSubjectRepo;
    private final JpaUserProfileRepository            profileRepo;
    private final JpaSchoolScoreConfigRepository      scoreConfigRepo;

    /**
     * Processes a CA import file.
     *
     * @param file          the uploaded .xlsx file
     * @param examinationId the examination to import scores into
     * @param schoolId      tenant scope
     * @param callerRole    "TEACHER" or "SCHOOL_ADMIN" — determines access guard
     * @param callerUserId  user ID of the caller — used to resolve teacherId for TEACHER guard
     */
    @Transactional
    public ImportResult importCaScores(
            MultipartFile file,
            Long examinationId,
            Long schoolId,
            String callerRole,
            Long callerUserId) throws IOException {

        ExcelImportUtil.validateFile(file);

        // ── Load and authorise examination ────────────────────────────────
        ExaminationEntity examination = examinationRepo
                .findByIdAndSchoolId(examinationId, schoolId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Examination", examinationId));

        if ("TEACHER".equals(callerRole)) {
            assertTeacherOwnsSubject(callerUserId, examination, schoolId);
        }

        BigDecimal configuredCaMax = examination.getTestMaxScore();

        // ── Parse CA component columns from header ────────────────────────
        // E.g. { "ca1" → 10, "ca2" → 20, "ca3" → 10 }
        LinkedHashMap<String, Integer> caColumns =
                ExcelImportUtil.parseCAColumns(file);

        if (caColumns.isEmpty()) {
            throw new IllegalArgumentException(
                    "No CA component columns detected. "
                    + "Expected at least one column with format 'Label/MaxScore' "
                    + "e.g. 'CA1/10', 'CA2/20'. "
                    + "Found headers: " + describeHeaders(file));
        }

        int rawMaxTotal = caColumns.values().stream().mapToInt(Integer::intValue).sum();
        if (rawMaxTotal <= 0) {
            throw new IllegalArgumentException(
                    "Sum of all CA component maxima is zero — check header values.");
        }

        // ── Parse all rows — "student email" column is required ───────────
        List<Map<String, String>> rows = ExcelImportUtil.parseRows(
                file, "student email");

        ImportResult.Builder result = ImportResult.builder().totalRows(rows.size());

        // Batch-load students by email in one query
        Set<String> emailsInFile = rows.stream()
                .map(r -> r.getOrDefault("student email", "").trim().toLowerCase())
                .filter(e -> !e.isBlank())
                .collect(Collectors.toSet());

        Map<String, StudentEntity> studentByEmail =
                studentRepo.findBySchoolIdAndEmailIn(schoolId, emailsInFile)
                        .stream()
                        .collect(Collectors.toMap(
                                s -> s.getEmail().toLowerCase(), s -> s));

        // Batch-load existing CA scores for this examination
        Map<Long, TestScoreEntity> existingByStudentId =
                testScoreRepo.findAllByExaminationIdAndSchoolId(examinationId, schoolId)
                        .stream()
                        .collect(Collectors.toMap(
                                ts -> ts.getStudent().getId(), ts -> ts));

        List<TestScoreEntity> toSave = new ArrayList<>();

        // ── Process rows ──────────────────────────────────────────────────
        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2;
            Map<String, String> row = rows.get(i);

            String email = row.getOrDefault("student email", "").trim().toLowerCase();
            if (email.isBlank()) {
                result.error(rowNum, "Student Email is required");
                continue;
            }

            StudentEntity student = studentByEmail.get(email);
            if (student == null) {
                result.error(rowNum, "No student found with email: " + email);
                continue;
            }

            // ── Per-component score reading ───────────────────────────────
            BigDecimal rawTotal = BigDecimal.ZERO;
            boolean rowHasError = false;

            for (Map.Entry<String, Integer> component : caColumns.entrySet()) {
                String colKey       = component.getKey();       // e.g. "ca1"
                int    componentMax = component.getValue();     // e.g. 10

                String rawVal = row.getOrDefault(colKey, "").trim();
                if (rawVal.isBlank()) {
                    // Blank = 0 for that component (absent = no score earned)
                    continue;
                }

                BigDecimal componentScore;
                try {
                    componentScore = new BigDecimal(rawVal);
                } catch (NumberFormatException e) {
                    result.error(rowNum,
                            "Invalid score for " + colKey + ": '" + rawVal + "' is not a number");
                    rowHasError = true;
                    break;
                }

                if (componentScore.compareTo(BigDecimal.ZERO) < 0) {
                    result.error(rowNum,
                            colKey + " score cannot be negative: " + componentScore);
                    rowHasError = true;
                    break;
                }

                BigDecimal componentMaxBd = BigDecimal.valueOf(componentMax);
                if (componentScore.compareTo(componentMaxBd) > 0) {
                    result.error(rowNum,
                            colKey + " score " + componentScore
                            + " exceeds its maximum of " + componentMax);
                    rowHasError = true;
                    break;
                }

                rawTotal = rawTotal.add(componentScore);
            }

            if (rowHasError) continue;

            // ── Resolve to configured CA max ──────────────────────────────
            // storedScore = (rawTotal / rawMaxTotal) × configuredCaMax
            BigDecimal storedScore = rawTotal
                    .multiply(configuredCaMax)
                    .divide(BigDecimal.valueOf(rawMaxTotal), 2, RoundingMode.HALF_UP);

            // ── Upsert ────────────────────────────────────────────────────
            TestScoreEntity existing = existingByStudentId.get(student.getId());
            if (existing != null) {
                existing.setScore(storedScore);
                toSave.add(existing);
            } else {
                toSave.add(TestScoreEntity.builder()
                        .examination(examination)
                        .student(student)
                        .score(storedScore)
                        .schoolId(schoolId)
                        .build());
            }
        }

        testScoreRepo.saveAll(toSave);

        int saved  = toSave.size();
        int failed = rows.size() - saved;
        result.successCount(saved).failureCount(failed);

        log.info("CA import: school={} exam={} components={} rawMax={} configuredMax={} "
                 + "saved={} failed={}",
                schoolId, examinationId, caColumns.size(),
                rawMaxTotal, configuredCaMax, saved, failed);

        return result.build();
    }

    /**
     * Returns a downloadable CA import template.
     * Default uses three components (CA1/10, CA2/20, CA3/10) — a common pattern.
     * The school admin is expected to adjust the maxima to match their scheme.
     */
    public byte[] buildCaTemplate() {
        return ExcelImportUtil.buildCATemplate(List.of(
                Map.entry("CA1", 10),
                Map.entry("CA2", 20),
                Map.entry("CA3", 10)));
    }

    /**
     * Returns a downloadable exam score import template.
     * Flat two-column format: Student Email | Score
     */
    public byte[] buildExamTemplate() {
        return ExcelImportUtil.buildTemplate(
                new String[]{ "Student Email", "Score" },
                new String[]{ "student@school.edu", "54" });
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Verifies the calling teacher is assigned to the subject on this examination.
     * Resolves teacher profileId from the caller's userId via user_profile table.
     */
    private void assertTeacherOwnsSubject(
            Long callerUserId, ExaminationEntity examination, Long schoolId) {

        Long teacherId = profileRepo
                .findByUserId(callerUserId)
                .filter(p -> p.getProfileType() == ProfileType.TEACHER)
                .map(p -> p.getProfileId())
                .orElseThrow(() -> new BusinessRuleViolationException(
                        "Your account is not linked to a teacher profile."));

        Long classSubjectId = examination.getClassSubject().getId();
        boolean assigned = teacherSubjectRepo
                .existsByTeacherIdAndClassSubjectIdAndSchoolId(
                        teacherId, classSubjectId, schoolId);

        if (!assigned) {
            throw new BusinessRuleViolationException(
                    "You are not assigned to this subject and cannot import scores for it.");
        }
    }

    /** Reads the raw header names from the file for error messages. */
    private static String describeHeaders(MultipartFile file) {
        try {
            return ExcelImportUtil.parseRows(file).stream()
                    .limit(0)            // just need the key set from the empty parse
                    .map(Map::keySet)
                    .findFirst()
                    .map(Object::toString)
                    .orElse("(could not read headers)");
        } catch (Exception e) {
            return "(could not read headers)";
        }
    }
}
