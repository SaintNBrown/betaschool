package com.betaschool.api.controller;

import com.betaschool.api.dto.request.CreateStudentRequest;
import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.StudentCommand.*;
import com.betaschool.query.model.StudentQuery.*;
import com.betaschool.query.model.StudentQueryResult.*;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/students")
@RequiredArgsConstructor
@Tag(name = "Students", description = "Student management and enrollment")
public class StudentController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

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
