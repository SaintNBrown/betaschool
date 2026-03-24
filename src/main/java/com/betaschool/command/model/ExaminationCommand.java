package com.betaschool.command.model;

import com.betaschool.shared.Command;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public sealed interface ExaminationCommand {

    record CreateExaminationCommand(
            Long termId,
            Long classSubjectId,
            LocalDate examDate,
            LocalTime examStartTime,       // optional — time exam starts on examDate
            Integer durationMinutes,       // optional — duration in minutes
            BigDecimal testMaxScore,       // optional — defaults to 40
            BigDecimal examMaxScore        // optional — defaults to 60; testMax + examMax must = 100
    ) implements Command<Long>, ExaminationCommand {}

    record UpdateExaminationCommand(
            Long examinationId,
            LocalDate examDate,
            LocalTime examStartTime,
            Integer durationMinutes,
            BigDecimal testMaxScore,
            BigDecimal examMaxScore
    ) implements Command<Void>, ExaminationCommand {}

    record RecordResultCommand(
            Long examinationId,
            Long studentId,
            BigDecimal score               // exam component score only
    ) implements Command<Long>, ExaminationCommand {}

    record UpdateResultCommand(
            Long resultId,
            BigDecimal score
    ) implements Command<Void>, ExaminationCommand {}

    record BulkRecordResultsCommand(
            Long examinationId,
            List<StudentScore> scores
    ) implements Command<Void>, ExaminationCommand {
        public record StudentScore(Long studentId, BigDecimal score, Long resultId) {}
    }

    /** Record the test/CA score for one student in an examination. */
    record RecordTestScoreCommand(
            Long examinationId,
            Long studentId,
            @NotNull @DecimalMin("0") BigDecimal score,
            String notes                   // optional e.g. "Test 1", "Mid-term CA"
    ) implements Command<Long>, ExaminationCommand {}

    /** Update an existing test score. */
    record UpdateTestScoreCommand(
            Long testScoreId,
            @NotNull @DecimalMin("0") BigDecimal score,
            String notes
    ) implements Command<Void>, ExaminationCommand {}

    /** Bulk record test scores for all students in one examination. */
    record BulkRecordTestScoresCommand(
            Long examinationId,
            List<StudentTestScore> scores
    ) implements Command<Void>, ExaminationCommand {
        public record StudentTestScore(Long studentId, BigDecimal score, String notes, Long testScoreId) {}
    }
}
