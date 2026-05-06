package com.betaschool.api.controller;

import com.betaschool.api.dto.request.BulkRecordResultsRequest;
import com.betaschool.api.dto.request.CreateExaminationRequest;
import com.betaschool.api.dto.request.RecordResultRequest;
import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.ExaminationCommand.*;
import com.betaschool.infrastructure.excel.CaScoreImportService;
import com.betaschool.infrastructure.excel.ImportResult;
import com.betaschool.infrastructure.excel.ScoreImportService;
import com.betaschool.infrastructure.excel.ScoreImportService.ScoreType;
import com.betaschool.query.model.AcademicQuery.GetExaminationsByTermQuery;
import com.betaschool.query.model.AcademicQuery.GetExistingScoresQuery;
import com.betaschool.query.model.AcademicQueryResult.ExaminationSummary;
import com.betaschool.query.model.AcademicQueryResult.ExistingScores;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final ScoreImportService    scoreImportService;
    private final CaScoreImportService  caScoreImportService;
    private final TenantGuard           tenantGuard;

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
    public ResponseEntity<ApiResponse<ExistingScores>> getExistingScores(@PathVariable Long examinationId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetExistingScoresQuery(examinationId))));
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

    // ── Score import endpoints ────────────────────────────────────────────

    @PostMapping(value = "/{examinationId}/scores/import",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "[SCHOOL_ADMIN] Import exam or CA scores from .xlsx",
               description = """
                   Accepts a .xlsx file with columns: Student Email | Score.
                   Query param scoreType=EXAM imports exam component scores (ResultEntity).
                   Query param scoreType=CA imports continuous assessment scores (TestScoreEntity).
                   Upserts: existing records for the same student are updated; new rows are created.
                   Maximum 500 rows, 5 MB file size.
                   Returns ImportResult with per-row error details.
                   """)
    public ResponseEntity<ApiResponse<ImportResult>> importScores(
            @PathVariable Long examinationId,
            @RequestParam ScoreType scoreType,
            @RequestParam("file") MultipartFile file) {
        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId = TenantContext.getSchoolId();
        try {
            ImportResult result = scoreImportService.importScores(
                    file, examinationId, scoreType, schoolId);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.<ImportResult>builder()
                            .success(false)
                            .message(e.getMessage())
                            .timestamp(java.time.OffsetDateTime.now())
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Score import failed: " + e.getMessage(), e);
        }
    }

    @GetMapping("/scores/import/template")
    @Operation(summary = "[SCHOOL_ADMIN] Download the .xlsx template for score import")
    public ResponseEntity<byte[]> scoreImportTemplate() {
        byte[] bytes = scoreImportService.buildTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"scores-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    // ── CA multi-component import ─────────────────────────────────────────

    @PostMapping(value = "/scores/import/ca",
                 consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
        summary = "[SCHOOL_ADMIN | TEACHER] Import CA scores from a multi-component .xlsx file",
        description = """
            Accepts a .xlsx file with columns:
              Student Email | CA1/10 | CA2/20 | CA3/10
            (any number of CA components, each header is Label/RawMax).

            Resolution: (sum of raw component scores / sum of raw maxima) × examination.testMaxScore
            Rounded to 2 decimal places. Stored as a single TestScoreEntity per student.

            Teachers can only import for examinations belonging to their assigned subjects.
            School admins can import for any examination in their school.

            Upserts: existing CA scores for the same student are updated; new ones are created.
            Maximum 500 rows, 5 MB file size.
            """)
    public ResponseEntity<ApiResponse<ImportResult>> importCaScores(
            @RequestParam Long examinationId,
            @RequestParam("file") MultipartFile file) {
        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN", "TEACHER");
        Long schoolId = TenantContext.getSchoolId();
        String role   = TenantContext.getUserRole();
        Long   userId = TenantContext.getUserId();
        try {
            ImportResult result = caScoreImportService.importCaScores(
                    file, examinationId, schoolId, role, userId);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (IllegalArgumentException
                 | com.betaschool.shared.exception.BusinessRuleViolationException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.<ImportResult>builder()
                            .success(false)
                            .message(e.getMessage())
                            .timestamp(java.time.OffsetDateTime.now())
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("CA import failed: " + e.getMessage(), e);
        }
    }

    @GetMapping("/scores/import/ca/template")
    @Operation(summary = "[SCHOOL_ADMIN | TEACHER] Download CA import template (.xlsx)",
               description = """
                   Returns a template with Student Email | CA1/10 | CA2/20 | CA3/10.
                   Adjust the Label/Max headers to match your actual CA scheme before distributing.
                   The school admin can customise component labels and maxima to any scheme.
                   """)
    public ResponseEntity<byte[]> caImportTemplate() {
        byte[] bytes = caScoreImportService.buildCaTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ca-scores-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/scores/import/exam/template")
    @Operation(summary = "[SCHOOL_ADMIN | TEACHER] Download exam score import template (.xlsx)",
               description = "Two-column format: Student Email | Score. "
                           + "Score must not exceed the examination's examMaxScore.")
    public ResponseEntity<byte[]> examImportTemplate() {
        byte[] bytes = caScoreImportService.buildExamTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"exam-scores-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

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
                Long testScoreId) {}  // null → create new; non-null → update existing
    }
}
