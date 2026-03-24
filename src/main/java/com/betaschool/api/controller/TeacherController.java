package com.betaschool.api.controller;

import com.betaschool.api.dto.request.CreateTeacherRequest;
import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.TeacherCommand.*;
import com.betaschool.query.model.TeacherQuery.*;
import com.betaschool.query.model.TeacherQueryResult.*;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teachers")
@RequiredArgsConstructor
@Tag(name = "Teachers", description = "Teacher management and assignment")
public class TeacherController {
    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @PostMapping
    @Operation(summary = "Register a new teacher")
    public ResponseEntity<ApiResponse<Long>> create(@Valid @RequestBody CreateTeacherRequest req) {
        Long id = commandBus.dispatch(new CreateTeacherCommand(req.surname(), req.otherNames(), req.email()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping
    @Operation(summary = "Get all teachers")
    public ResponseEntity<ApiResponse<List<TeacherSummary>>> getAll() {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetAllTeachersQuery())));
    }

    @GetMapping("/{teacherId}")
    @Operation(summary = "Get teacher by ID")
    public ResponseEntity<ApiResponse<TeacherDetail>> getById(@PathVariable Long teacherId) {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetTeacherByIdQuery(teacherId))));
    }

    @PostMapping("/{teacherId}/assign/class-session/{classSessionId}")
    @Operation(summary = "Assign teacher to a class-session")
    public ResponseEntity<ApiResponse<Long>> assignToClass(
            @PathVariable Long teacherId,
            @PathVariable Long classSessionId,
            @RequestParam(defaultValue = "false") boolean isFormTeacher) {
        Long id = commandBus.dispatch(new AssignTeacherToClassCommand(teacherId, classSessionId, isFormTeacher));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @PostMapping("/{teacherId}/assign/subject/{classSubjectId}")
    @Operation(summary = "Assign teacher to a class-subject (subject must have no teacher yet)")
    public ResponseEntity<ApiResponse<Long>> assignToSubject(
            @PathVariable Long teacherId,
            @PathVariable Long classSubjectId) {
        Long id = commandBus.dispatch(new AssignTeacherToSubjectCommand(teacherId, classSubjectId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @DeleteMapping("/{teacherId}/assign/subject/{classSubjectId}")
    @Operation(summary = "Unassign teacher from a class-subject")
    public ResponseEntity<ApiResponse<Void>> unassignFromSubject(
            @PathVariable Long teacherId,
            @PathVariable Long classSubjectId) {
        commandBus.dispatch(new UnassignTeacherFromSubjectCommand(teacherId, classSubjectId));
        return ResponseEntity.ok(ApiResponse.noContent("Teacher unassigned from subject"));
    }

    @PutMapping("/{teacherId}/assign/subject/{classSubjectId}")
    @Operation(summary = "Reassign a class-subject to a different teacher (replaces existing assignment atomically)")
    public ResponseEntity<ApiResponse<Void>> reassignToSubject(
            @PathVariable Long teacherId,
            @PathVariable Long classSubjectId) {
        commandBus.dispatch(new ReassignTeacherToSubjectCommand(teacherId, classSubjectId));
        return ResponseEntity.ok(ApiResponse.noContent("Subject reassigned to new teacher"));
    }

    @PostMapping("/{teacherId}/assign/class-session/{classSessionId}/all-subjects")
    @Operation(summary = "Assign form teacher to ALL subjects in a class-session (primary school model)",
            description = """
                       Assigns the teacher to every subject in the class-session in a single operation.
                       This supports the primary school model where the class teacher teaches all subjects.
 
                       Behaviour:
                       - Subjects with no teacher assigned → assigned to this teacher.
                       - Subjects already assigned to THIS teacher → left unchanged (idempotent).
                       - Subjects already assigned to a DIFFERENT teacher → SKIPPED (specialist teachers preserved).
                       - Pass setAsFormTeacher=true to also mark this teacher as the form teacher of the class.
 
                       The response body lists how many subjects were assigned, already owned, and skipped,
                       along with the names of any skipped subjects so the admin can review them.
                       """)
    public ResponseEntity<ApiResponse<FormTeacherAssignmentResult>> assignAllSubjects(
            @PathVariable Long teacherId,
            @PathVariable Long classSessionId,
            @RequestParam(defaultValue = "true") boolean setAsFormTeacher) {
        FormTeacherAssignmentResult result = commandBus.dispatch(
                new AssignFormTeacherToAllSubjectsCommand(teacherId, classSessionId, setAsFormTeacher));
        return ResponseEntity.ok(ApiResponse.ok(
                "Form teacher assigned to " + result.assigned() + " subject(s). "
                        + (result.skippedOtherTeacher() > 0
                        ? result.skippedOtherTeacher() + " subject(s) skipped (already have specialist teachers)."
                        : ""),
                result));
    }

    @GetMapping("/{teacherId}/classes")
    @Operation(summary = "Get all classes a teacher is assigned to in a session")
    public ResponseEntity<ApiResponse<List<TeacherClassItem>>> getClasses(
            @PathVariable Long teacherId,
            @RequestParam Long sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetTeacherClassesQuery(teacherId, sessionId))));
    }

    @GetMapping("/{teacherId}/subjects")
    @Operation(summary = "Get all subjects a teacher is assigned to in a session")
    public ResponseEntity<ApiResponse<List<TeacherSubjectItem>>> getSubjects(
            @PathVariable Long teacherId,
            @RequestParam Long sessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetTeacherSubjectsQuery(teacherId, sessionId))));
    }
}
