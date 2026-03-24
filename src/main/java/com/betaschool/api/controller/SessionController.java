package com.betaschool.api.controller;

import com.betaschool.api.dto.request.CreateSessionRequest;
import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.AcademicCommand.*;
import com.betaschool.query.model.AcademicQuery.*;
import com.betaschool.query.model.AcademicQueryResult.*;
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
@RequestMapping("/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Academic session management")
public class SessionController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @PostMapping
    @Operation(summary = "Create a new academic session")
    public ResponseEntity<ApiResponse<Long>> create(@Valid @RequestBody CreateSessionRequest req) {
        Long id = commandBus.dispatch(new CreateSessionCommand(
                req.sessionName(), req.startDate(), req.closingDate()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping
    @Operation(summary = "Get all academic sessions")
    public ResponseEntity<ApiResponse<List<SessionSummary>>> getAll() {
        List<SessionSummary> sessions = queryBus.dispatch(new GetAllSessionsQuery());
        return ResponseEntity.ok(ApiResponse.ok(sessions));
    }

    /**
     * Issue 5: Returns the currently active session for the school.
     * Any authenticated school user may call this to discover the active session
     * without needing to know a session ID in advance.
     */
    @GetMapping("/current")
    @Operation(summary = "Get the currently active academic session for this school")
    public ResponseEntity<ApiResponse<SessionSummary>> getCurrent() {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetCurrentSessionQuery())));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get session details with all classes")
    public ResponseEntity<ApiResponse<SessionDetail>> getById(@PathVariable Long sessionId) {
        SessionDetail detail = queryBus.dispatch(new GetSessionByIdQuery(sessionId));
        return ResponseEntity.ok(ApiResponse.ok(detail));
    }

    /**
     * Issue 5: Designates a session as the school's current/active session.
     * Only SCHOOL_ADMIN (and SYSTEM_ADMIN) may call this.
     */
    @PatchMapping("/{sessionId}/set-current")
    @Operation(summary = "[SCHOOL_ADMIN] Set a session as the current active session")
    public ResponseEntity<ApiResponse<Void>> setCurrentSession(@PathVariable Long sessionId) {
        commandBus.dispatch(new SetCurrentSessionCommand(sessionId));
        return ResponseEntity.ok(ApiResponse.noContent("Session set as current"));
    }

    @PostMapping("/{sessionId}/classes/{classId}")
    @Operation(summary = "Open a class within a session")
    public ResponseEntity<ApiResponse<Long>> openClassInSession(
            @PathVariable Long sessionId,
            @PathVariable Long classId) {
        Long id = commandBus.dispatch(new OpenClassInSessionCommand(classId, sessionId));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping("/{sessionId}/classes")
    @Operation(summary = "Get all class-sessions for a session")
    public ResponseEntity<ApiResponse<List<ClassSessionSummary>>> getClassSessions(
            @PathVariable Long sessionId) {
        List<ClassSessionSummary> cs = queryBus.dispatch(new GetClassSessionsQuery(sessionId));
        return ResponseEntity.ok(ApiResponse.ok(cs));
    }
}
