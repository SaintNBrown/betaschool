package com.betaschool.infrastructure.excel;

import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Processes score import files for a specific examination.
 *
 * Two modes:
 *   EXAM — upserts ResultEntity (exam component score)
 *   CA   — upserts TestScoreEntity (continuous assessment score)
 *
 * Both modes are idempotent: existing records are updated, missing ones are
 * created. All valid rows commit together in one transaction.
 *
 * Score state machine note: ResultEntity and TestScoreEntity do not yet have
 * a status column (the score state machine from Prompt 1 is not yet implemented).
 * The SUBMITTED/LOCKED guard below is a no-op stub — activate it once the
 * status field exists on both entities.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScoreImportService {

    private final JpaExaminationRepository    examinationRepo;
    private final JpaStudentRepository        studentRepo;
    private final JpaResultRepository         resultRepo;
    private final JpaTestScoreRepository      testScoreRepo;
    private final JpaSchoolScoreConfigRepository scoreConfigRepo;

    public enum ScoreType { EXAM, CA }

    @Transactional
    public ImportResult importScores(
            MultipartFile file, Long examinationId,
            ScoreType scoreType, Long schoolId) throws IOException {

        ExcelImportUtil.validateFile(file);

        // ── Load and verify the examination ──────────────────────────────
        ExaminationEntity examination = examinationRepo
                .findByIdAndSchoolId(examinationId, schoolId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Examination", examinationId));

        BigDecimal maxScore = scoreType == ScoreType.EXAM
                ? examination.getExamMaxScore()
                : examination.getTestMaxScore();

        // ── Load school score config for grade computation ────────────────
        SchoolScoreConfigEntity config = scoreConfigRepo
                .findBySchoolId(schoolId)
                .orElseGet(() -> SchoolScoreConfigEntity.builder()
                        .schoolId(schoolId).build());

        // ── Parse file ────────────────────────────────────────────────────
        List<Map<String, String>> rows = ExcelImportUtil.parseRows(
                file, "student email", "score");

        ImportResult.Builder result = ImportResult.builder().totalRows(rows.size());

        // ── Collect all emails from the file then batch-load students ─────
        Set<String> emailsInFile = rows.stream()
                .map(r -> r.getOrDefault("student email", "").trim().toLowerCase())
                .filter(e -> !e.isBlank())
                .collect(Collectors.toSet());

        Map<String, StudentEntity> studentByEmail =
                studentRepo.findBySchoolIdAndEmailIn(schoolId, emailsInFile)
                        .stream()
                        .collect(Collectors.toMap(
                                s -> s.getEmail().toLowerCase(),
                                s -> s));

        // ── Batch-load existing records for this examination ──────────────
        // Keyed by studentId for O(1) lookup during row processing.
        Map<Long, ResultEntity>    existingResults;
        Map<Long, TestScoreEntity> existingTestScores;

        if (scoreType == ScoreType.EXAM) {
            existingResults = resultRepo
                    .findAllByExaminationIdAndSchoolId(examinationId, schoolId)
                    .stream()
                    .collect(Collectors.toMap(r -> r.getStudent().getId(), r -> r));
            existingTestScores = Collections.emptyMap();
        } else {
            existingTestScores = testScoreRepo
                    .findAllByExaminationIdAndSchoolId(examinationId, schoolId)
                    .stream()
                    .collect(Collectors.toMap(ts -> ts.getStudent().getId(), ts -> ts));
            existingResults = Collections.emptyMap();
        }

        // ── Process rows ──────────────────────────────────────────────────
        List<ResultEntity>    resultsToSave    = new ArrayList<>();
        List<TestScoreEntity> testScoresToSave = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2; // 1-indexed, row 1 is header
            Map<String, String> row = rows.get(i);

            String email    = row.getOrDefault("student email", "").trim().toLowerCase();
            String scoreStr = row.getOrDefault("score", "").trim();

            // ── Per-row validation ─────────────────────────────────────────
            if (email.isBlank()) {
                result.error(rowNum, "Student Email is required");
                continue;
            }

            StudentEntity student = studentByEmail.get(email);
            if (student == null) {
                result.error(rowNum, "No student found with email: " + email);
                continue;
            }

            if (scoreStr.isBlank()) {
                result.error(rowNum, "Score is required for " + email);
                continue;
            }

            BigDecimal score;
            try {
                score = new BigDecimal(scoreStr);
            } catch (NumberFormatException e) {
                result.error(rowNum, "Score '" + scoreStr + "' is not a valid number");
                continue;
            }

            if (score.compareTo(BigDecimal.ZERO) < 0) {
                result.error(rowNum, "Score cannot be negative: " + score);
                continue;
            }
            if (score.compareTo(maxScore) > 0) {
                result.error(rowNum,
                        "Score " + score + " exceeds maximum of " + maxScore
                        + " for this examination");
                continue;
            }

            // ── Score state machine guard (no-op until status field exists) ─
            // TODO: Once ResultEntity/TestScoreEntity have a `status` column,
            //       add: if (existing.getStatus() == SUBMITTED || LOCKED) { error; continue; }

            // ── Upsert ─────────────────────────────────────────────────────
            if (scoreType == ScoreType.EXAM) {
                ResultEntity existing = existingResults.get(student.getId());
                if (existing != null) {
                    // Update — recompute grade with latest CA score if available
                    existing.setScore(score);
                    BigDecimal caScore = testScoreRepo
                            .findByExaminationIdAndStudentIdAndSchoolId(
                                    examinationId, student.getId(), schoolId)
                            .map(TestScoreEntity::getScore)
                            .orElse(BigDecimal.ZERO);
                    existing.setGrade(computeGrade(score, caScore, config));
                    resultsToSave.add(existing);
                } else {
                    // Insert
                    BigDecimal caScore = testScoreRepo
                            .findByExaminationIdAndStudentIdAndSchoolId(
                                    examinationId, student.getId(), schoolId)
                            .map(TestScoreEntity::getScore)
                            .orElse(BigDecimal.ZERO);
                    resultsToSave.add(ResultEntity.builder()
                            .examination(examination)
                            .student(student)
                            .score(score)
                            .grade(computeGrade(score, caScore, config))
                            .schoolId(schoolId)
                            .build());
                }
            } else {
                // CA / TestScore
                TestScoreEntity existing = existingTestScores.get(student.getId());
                if (existing != null) {
                    existing.setScore(score);
                    // Also refresh the grade on any existing ResultEntity
                    resultRepo.findByExaminationIdAndStudentIdAndSchoolId(
                            examinationId, student.getId(), schoolId)
                            .ifPresent(r -> {
                                r.setGrade(computeGrade(r.getScore(), score, config));
                                resultRepo.save(r);
                            });
                    testScoresToSave.add(existing);
                } else {
                    testScoresToSave.add(TestScoreEntity.builder()
                            .examination(examination)
                            .student(student)
                            .score(score)
                            .schoolId(schoolId)
                            .build());
                }
            }
        }

        // ── Commit all valid rows ──────────────────────────────────────────
        if (!resultsToSave.isEmpty())    resultRepo.saveAll(resultsToSave);
        if (!testScoresToSave.isEmpty()) testScoreRepo.saveAll(testScoresToSave);

        int saved  = resultsToSave.size() + testScoresToSave.size();
        int failed = rows.size() - saved;
        result.successCount(saved).failureCount(failed);

        log.info("Score import: school={} exam={} type={} total={} saved={} failed={}",
                schoolId, examinationId, scoreType, rows.size(), saved, failed);
        return result.build();
    }

    /** Returns raw bytes of a downloadable .xlsx template for score import. */
    public byte[] buildTemplate() {
        return ExcelImportUtil.buildTemplate(
                new String[]{ "Student Email", "Score" },
                new String[]{ "chukwuemeka@school.edu", "72" });
    }

    private static String computeGrade(BigDecimal examScore, BigDecimal testScore,
                                        SchoolScoreConfigEntity config) {
        if (examScore == null) return null;
        BigDecimal combined = examScore.add(
                testScore != null ? testScore : BigDecimal.ZERO);
        return config.resolveGrade(combined.intValue());
    }
}
