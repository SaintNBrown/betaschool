package com.betaschool.api.controller;

import com.betaschool.api.dto.request.CreateStudentRequest;
import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.StudentCommand.CreateStudentCommand;
import com.betaschool.command.model.StudentCommand.EnrollStudentInClassCommand;
import com.betaschool.command.model.StudentCommand.EnrollStudentInSubjectCommand;
import com.betaschool.command.model.StudentCommand.UnenrollStudentFromSubjectCommand;
import com.betaschool.infrastructure.excel.ImportResult;
import com.betaschool.infrastructure.excel.StudentImportService;
import com.betaschool.query.model.StudentQuery.*;
import com.betaschool.query.model.StudentQueryResult.*;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/students")
@RequiredArgsConstructor
@Tag(name = "Students", description = "Student management and enrollment")
public class StudentController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;
    private final StudentImportService importService;
    private final TenantGuard tenantGuard;

    @PostMapping
    @Operation(summary = "Register a new student")
    public ResponseEntity<ApiResponse<Long>> create(@Valid @RequestBody CreateStudentRequest req) {
        Long id = commandBus.dispatch(new CreateStudentCommand(req.surname(), req.otherNames(), req.email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping("/{studentId}")
    @Operation(summary = "Get student by ID")
    public ResponseEntity<ApiResponse<StudentDetail>> getById(@PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetStudentByIdQuery(studentId))));
    }

    @GetMapping
    @Operation(summary = "Search students by name")
    public ResponseEntity<ApiResponse<Page<StudentSummary>>> search(
            @RequestParam(defaultValue = "") String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new SearchStudentsQuery(q, pageable))));
    }

    @PostMapping("/{studentId}/enroll/class-session/{classSessionId}")
    @Operation(summary = "Enroll student in a class-session (auto-enrolls in compulsory subjects)")
    public ResponseEntity<ApiResponse<Long>> enrollInClass(
            @PathVariable Long studentId,
            @PathVariable Long classSessionId) {
        Long id = commandBus.dispatch(new EnrollStudentInClassCommand(studentId, classSessionId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PostMapping("/{studentId}/enroll/subject/{classSubjectId}")
    @Operation(summary = "Enroll student in an elective subject")
    public ResponseEntity<ApiResponse<Long>> enrollInSubject(
            @PathVariable Long studentId,
            @PathVariable Long classSubjectId) {
        Long id = commandBus.dispatch(new EnrollStudentInSubjectCommand(studentId, classSubjectId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @DeleteMapping("/{studentId}/enroll/subject/{classSubjectId}")
    @Operation(summary = "Unenroll student from an elective subject")
    public ResponseEntity<ApiResponse<Void>> unenrollFromSubject(
            @PathVariable Long studentId,
            @PathVariable Long classSubjectId) {
        commandBus.dispatch(new UnenrollStudentFromSubjectCommand(studentId, classSubjectId));
        return ResponseEntity.ok(ApiResponse.noContent("Unenrolled from subject"));
    }

    @GetMapping("/{studentId}/subjects")
    @Operation(summary = "Get all subjects a student is enrolled in within a class-session")
    public ResponseEntity<ApiResponse<List<StudentSubjectItem>>> getSubjects(
            @PathVariable Long studentId,
            @RequestParam Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetStudentSubjectsQuery(studentId, classSessionId))));
    }

    @GetMapping("/{studentId}/report-card")
    @Operation(summary = "Get student report card for a specific term")
    public ResponseEntity<ApiResponse<ReportCard>> getReportCard(
            @PathVariable Long studentId,
            @RequestParam Long termId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetStudentReportCardQuery(studentId, termId))));
    }

    @GetMapping("/{studentId}/transcript")
    @Operation(summary = "Get full session transcript for a student")
    public ResponseEntity<ApiResponse<FullTranscript>> getTranscript(
            @PathVariable Long studentId,
            @RequestParam Long sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetStudentFullTranscriptQuery(studentId, sessionId))));
    }

    @GetMapping("/class-session/{classSessionId}")
    @Operation(summary = "Get all students in a class-session")
    public ResponseEntity<ApiResponse<List<StudentSummary>>> getByClassSession(
            @PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetStudentsByClassSessionQuery(classSessionId))));
    }

    // ── Import endpoints ──────────────────────────────────────────────────

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "[SCHOOL_ADMIN] Import students from an .xlsx file",
               description = """
                   Accepts a .xlsx file with columns: Surname | Other Names | Email (optional unless createAccounts=true).
                   Row 1 must be the header. Maximum 500 data rows, 5 MB file size.
                   Valid rows are committed; invalid rows are reported without failing the batch.

                   createAccounts=false (default): creates student profile records only.
                   createAccounts=true: also creates a login account (AppUser + UserProfile) for each
                   student. Email becomes required. Returns temporary passwords in createdAccounts[].
                   These passwords are shown exactly once in the response — save them before dismissing.
                   """)
    public ResponseEntity<ApiResponse<ImportResult>> importStudents(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "createAccounts", defaultValue = "false") boolean createAccounts) {
        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId = TenantContext.getSchoolId();
        try {
            ImportResult result = importService.importStudents(file, schoolId, createAccounts);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (IllegalArgumentException e) {
            // File-level validation errors (size, format, too many rows)
            return ResponseEntity.badRequest()
                    .body(ApiResponse.<ImportResult>builder()
                            .success(false)
                            .message(e.getMessage())
                            .timestamp(java.time.OffsetDateTime.now())
                            .build());
        } catch (Exception e) {
            throw new RuntimeException("Student import failed: " + e.getMessage(), e);
        }
    }

    @GetMapping("/import/template")
    @Operation(summary = "[SCHOOL_ADMIN] Download the .xlsx template for student import",
               description = "The template is the same regardless of createAccounts — "
                           + "no password column is needed (passwords are auto-generated).")
    public ResponseEntity<byte[]> studentImportTemplate() {
        // includeAccounts query param accepted but ignored — template is always identical
        byte[] bytes = importService.buildTemplate();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"students-import-template.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    @GetMapping("/class-subject/{classSubjectId}")
    @Operation(summary = "Get students enrolled in a specific subject — use this for result entry screens",
               description = "Returns only students enrolled in this subject. " +
                             "Teachers are restricted to subjects they are assigned to.")
    public ResponseEntity<ApiResponse<List<StudentSummary>>> getByClassSubject(
            @PathVariable Long classSubjectId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetStudentsByClassSubjectQuery(classSubjectId))));
    }
}
