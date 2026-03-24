package com.betaschool.api.controller;

import com.betaschool.api.dto.request.BulkRecordResultsRequest;
import com.betaschool.api.dto.request.CreateExaminationRequest;
import com.betaschool.api.dto.request.RecordResultRequest;
import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.ExaminationCommand.*;
import com.betaschool.query.model.AcademicQuery;
import com.betaschool.query.model.AcademicQuery.GetExaminationsByTermQuery;
import com.betaschool.query.model.AcademicQueryResult;
import com.betaschool.query.model.AcademicQueryResult.ExaminationSummary;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/examinations")
@RequiredArgsConstructor
@Tag(name = "Examinations", description = "Examination and result management")
public class ExaminationController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    // ── Examinations ───────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "[SCHOOL_ADMIN] Create an examination. testMaxScore + examMaxScore must equal 100 (defaults: 40 + 60).")
    public ResponseEntity<ApiResponse<Long>> create(@Valid @RequestBody CreateExaminationRequest req) {
        Long id = commandBus.dispatch(new CreateExaminationCommand(
                req.termId(), req.classSubjectId(), req.examDate(),
                req.examStartTime(), req.durationMinutes(),
                req.testMaxScore(), req.examMaxScore()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PutMapping("/{examinationId}")
    @Operation(summary = "[SCHOOL_ADMIN] Update examination date, time, duration or score weights")
    public ResponseEntity<ApiResponse<Void>> update(
            @PathVariable Long examinationId,
            @RequestBody UpdateExaminationRequest req) {
        commandBus.dispatch(new UpdateExaminationCommand(
                examinationId, req.examDate(), req.examStartTime(),
                req.durationMinutes(), req.testMaxScore(), req.examMaxScore()));
        return ResponseEntity.ok(ApiResponse.noContent("Examination updated"));
    }

    @GetMapping("/term/{termId}")
    @Operation(summary = "Get all examinations in a term")
    public ResponseEntity<ApiResponse<List<ExaminationSummary>>> getByTerm(@PathVariable Long termId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetExaminationsByTermQuery(termId))));
    }

    @GetMapping("/{examinationId}/scores")
    @Operation(summary = "Get all already-recorded exam and CA scores for an examination",
            description = "Returns maps of studentId → score for both exam results and CA/test scores. " +
                    "Use this when opening the result entry modal to pre-populate existing scores.")
    public ResponseEntity<ApiResponse<AcademicQueryResult.ExistingScores>> getExistingScores(@PathVariable Long examinationId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new AcademicQuery.GetExistingScoresQuery(examinationId))));
    }

    // ── Exam Results ───────────────────────────────────────────────────────

    @PostMapping("/{examinationId}/results")
    @Operation(summary = "Record the exam score for one student (exam component only; up to examMaxScore)")
    public ResponseEntity<ApiResponse<Long>> recordResult(
            @PathVariable Long examinationId,
            @Valid @RequestBody RecordResultRequest req) {
        Long id = commandBus.dispatch(new RecordResultCommand(examinationId, req.studentId(), req.score()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PostMapping("/{examinationId}/results/bulk")
    @Operation(summary = "Bulk record exam scores for all students (atomic — all or nothing)")
    public ResponseEntity<ApiResponse<Void>> bulkRecordResults(
            @PathVariable Long examinationId,
            @Valid @RequestBody BulkRecordResultsRequest req) {
        List<BulkRecordResultsCommand.StudentScore> scores = req.scores().stream()
                .map(s -> new BulkRecordResultsCommand.StudentScore(s.studentId(), s.score(), s.resultId()))
                .collect(Collectors.toList());
        commandBus.dispatch(new BulkRecordResultsCommand(examinationId, scores));
        return ResponseEntity.ok(ApiResponse.noContent("Exam results recorded for " + scores.size() + " students"));
    }

    @PutMapping("/results/{resultId}")
    @Operation(summary = "Update an existing exam result score")
    public ResponseEntity<ApiResponse<Void>> updateResult(
            @PathVariable Long resultId,
            @Valid @RequestBody RecordResultRequest req) {
        commandBus.dispatch(new UpdateResultCommand(resultId, req.score()));
        return ResponseEntity.ok(ApiResponse.noContent("Result updated"));
    }

    // ── Test / CA Scores ───────────────────────────────────────────────────

    @PostMapping("/{examinationId}/test-scores")
    @Operation(summary = "Record the test/CA score for one student (up to testMaxScore)")
    public ResponseEntity<ApiResponse<Long>> recordTestScore(
            @PathVariable Long examinationId,
            @Valid @RequestBody RecordTestScoreRequest req) {
        Long id = commandBus.dispatch(new RecordTestScoreCommand(
                examinationId, req.studentId(), req.score(), req.notes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PostMapping("/{examinationId}/test-scores/bulk")
    @Operation(summary = "Bulk record test/CA scores for all students (atomic — all or nothing)")
    public ResponseEntity<ApiResponse<Void>> bulkRecordTestScores(
            @PathVariable Long examinationId,
            @Valid @RequestBody BulkRecordTestScoresRequest req) {
        List<BulkRecordTestScoresCommand.StudentTestScore> scores = req.scores().stream()
                .map(s -> new BulkRecordTestScoresCommand.StudentTestScore(s.studentId(), s.score(), s.notes(), s.testScoreId()))
                .collect(Collectors.toList());
        commandBus.dispatch(new BulkRecordTestScoresCommand(examinationId, scores));
        return ResponseEntity.ok(ApiResponse.noContent("Test scores recorded for " + scores.size() + " students"));
    }

    @PutMapping("/test-scores/{testScoreId}")
    @Operation(summary = "Update an existing test/CA score")
    public ResponseEntity<ApiResponse<Void>> updateTestScore(
            @PathVariable Long testScoreId,
            @Valid @RequestBody UpdateTestScoreRequest req) {
        commandBus.dispatch(new UpdateTestScoreCommand(testScoreId, req.score(), req.notes()));
        return ResponseEntity.ok(ApiResponse.noContent("Test score updated"));
    }

    // ── Request records ────────────────────────────────────────────────────

    public record UpdateExaminationRequest(
            LocalDate examDate, LocalTime examStartTime,
            Integer durationMinutes, BigDecimal testMaxScore, BigDecimal examMaxScore) {}

    public record RecordTestScoreRequest(
            @NotNull Long studentId,
            @NotNull @DecimalMin("0") BigDecimal score,
            String notes) {}

    public record UpdateTestScoreRequest(
            @NotNull @DecimalMin("0") BigDecimal score,
            String notes) {}

    public record BulkRecordTestScoresRequest(
            @jakarta.validation.constraints.NotEmpty
            @Valid List<TestScoreEntry> scores) {
        public record TestScoreEntry(
                @NotNull Long studentId,
                @NotNull @DecimalMin("0") BigDecimal score,
                String notes,
                Long testScoreId) {}  // null → create; non-null → update existing
    }
}
