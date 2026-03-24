package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.AcademicCommand.AddSubjectToClassCommand;
import com.betaschool.command.model.AcademicCommand.CreateClassCommand;
import com.betaschool.command.model.AcademicCommand.CreateTermCommand;
import com.betaschool.command.model.AcademicCommand.UpdateClassSubjectTypeCommand;
import com.betaschool.query.model.AcademicQuery.GetAllClassesQuery;
import com.betaschool.query.model.AcademicQuery.GetClassSubjectsQuery;
import com.betaschool.query.model.AcademicQuery.GetElectiveSubjectsQuery;
import com.betaschool.query.model.AcademicQuery.GetTermsByClassSessionQuery;
import com.betaschool.query.model.AcademicQueryResult.ClassSubjectItem;
import com.betaschool.query.model.AcademicQueryResult.ClassSummary;
import com.betaschool.query.model.AcademicQueryResult.TermSummary;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/classes")
@RequiredArgsConstructor
@Tag(name = "Classes", description = "Class management")
public class ClassController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @PostMapping
    @Operation(summary = "Create a new class")
    public ResponseEntity<ApiResponse<Long>> create(@RequestParam String name) {
        Long id = commandBus.dispatch(new CreateClassCommand(name));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping
    @Operation(summary = "Get all classes")
    public ResponseEntity<ApiResponse<List<ClassSummary>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetAllClassesQuery())));
    }

    @GetMapping("/class-sessions/{classSessionId}/subjects")
    @Operation(summary = "Get all subjects for a class-session (compulsory and elective)")
    public ResponseEntity<ApiResponse<List<ClassSubjectItem>>> getSubjects(
            @PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetClassSubjectsQuery(classSessionId))));
    }

    @GetMapping("/class-sessions/{classSessionId}/subjects/elective")
    @Operation(summary = "Get only elective subjects for a class-session — use this to populate the elective enrollment list")
    public ResponseEntity<ApiResponse<List<ClassSubjectItem>>> getElectiveSubjects(
            @PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetElectiveSubjectsQuery(classSessionId))));
    }

    @PostMapping("/class-sessions/{classSessionId}/subjects/{subjectId}")
    @Operation(summary = "Add a subject to a class-session")
    public ResponseEntity<ApiResponse<Long>> addSubject(
            @PathVariable Long classSessionId,
            @PathVariable Long subjectId,
            @RequestParam(defaultValue = "false") boolean isElective) {
        Long id = commandBus.dispatch(new AddSubjectToClassCommand(classSessionId, subjectId, isElective));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PatchMapping("/class-sessions/subjects/{classSubjectId}/type")
    @Operation(summary = "[SCHOOL_ADMIN] Toggle a subject between compulsory and elective. "
            + "Switching to compulsory auto-enrolls all currently enrolled class students.")
    public ResponseEntity<ApiResponse<Void>> updateSubjectType(
            @PathVariable Long classSubjectId,
            @RequestParam boolean isElective) {
        commandBus.dispatch(new UpdateClassSubjectTypeCommand(classSubjectId, isElective));
        return ResponseEntity.ok(ApiResponse.noContent(
                isElective ? "Subject changed to elective" : "Subject changed to compulsory"));
    }

    @GetMapping("/class-sessions/{classSessionId}/terms")
    @Operation(summary = "Get all terms for a class-session")
    public ResponseEntity<ApiResponse<List<TermSummary>>> getTerms(@PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetTermsByClassSessionQuery(classSessionId))));
    }

    @PostMapping("/class-sessions/{classSessionId}/terms")
    @Operation(summary = "Create a term for a class-session")
    public ResponseEntity<ApiResponse<Long>> createTerm(
            @PathVariable Long classSessionId,
            @RequestParam Integer termNumber) {
        Long id = commandBus.dispatch(new CreateTermCommand(classSessionId, termNumber));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }
}
