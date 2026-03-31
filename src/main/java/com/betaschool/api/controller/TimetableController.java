package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.TimetableCommand.DeleteTimetableVersionCommand;
import com.betaschool.command.model.TimetableCommand.PublishTimetableCommand;
import com.betaschool.command.model.TimetableCommand.TimetableSlotInput;
import com.betaschool.query.model.TimetableQuery.GetActiveTimetableQuery;
import com.betaschool.query.model.TimetableQuery.GetTimetableByIdQuery;
import com.betaschool.query.model.TimetableQuery.GetTimetableConflictsQuery;
import com.betaschool.query.model.TimetableQuery.GetTimetableHistoryQuery;
import com.betaschool.query.model.TimetableQueryResult.TeacherConflict;
import com.betaschool.query.model.TimetableQueryResult.TimetableDetail;
import com.betaschool.query.model.TimetableQueryResult.TimetableSummary;
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
@RequestMapping("/timetables")
@RequiredArgsConstructor
@Tag(name = "Timetables", description = "Weekly timetable management per class-session")
public class TimetableController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    @PostMapping("/class-sessions/{classSessionId}")
    @Operation(summary = "[SCHOOL_ADMIN] Publish a new timetable for a class-session. "
            + "Replaces the current active timetable and creates a new version. "
            + "Up to 5 historical versions are retained; oldest are auto-deleted beyond that.")
    public ResponseEntity<ApiResponse<Long>> publish(
            @PathVariable Long classSessionId,
            @Valid @RequestBody PublishTimetableRequest req) {
        Long id = commandBus.dispatch(new PublishTimetableCommand(
                classSessionId, req.notes(), req.slots()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(id));
    }

    @GetMapping("/class-sessions/{classSessionId}/active")
    @Operation(summary = "Get the currently active timetable for a class-session")
    public ResponseEntity<ApiResponse<TimetableDetail>> getActive(
            @PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetActiveTimetableQuery(classSessionId))));
    }

    @GetMapping("/class-sessions/{classSessionId}/history")
    @Operation(summary = "[SCHOOL_ADMIN] Get all timetable versions for a class-session, newest first")
    public ResponseEntity<ApiResponse<List<TimetableSummary>>> getHistory(
            @PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetTimetableHistoryQuery(classSessionId))));
    }

    @GetMapping("/class-sessions/{classSessionId}/conflicts")
    @Operation(summary = "[SCHOOL_ADMIN] Check the active timetable for cross-class teacher conflicts",
               description = """
                       Runs the same teacher conflict detection used during publishing, but returns
                       the list of conflicts without throwing — so the admin can review them before
                       attempting to publish an updated timetable.

                       Returns an empty list when no conflicts exist (the happy path).
                       Each conflict entry names the teacher, the clashing class, the day, the
                       time window, and both subject names so the issue is immediately actionable.
                       """)
    public ResponseEntity<ApiResponse<List<TeacherConflict>>> getConflicts(
            @PathVariable Long classSessionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetTimetableConflictsQuery(classSessionId))));
    }

    @GetMapping("/{timetableId}")
    @Operation(summary = "Get a specific timetable version by ID")
    public ResponseEntity<ApiResponse<TimetableDetail>> getById(
            @PathVariable Long timetableId) {
        return ResponseEntity.ok(ApiResponse.ok(
                queryBus.dispatch(new GetTimetableByIdQuery(timetableId))));
    }

    @DeleteMapping("/{timetableId}")
    @Operation(summary = "[SCHOOL_ADMIN] Delete a historical (inactive) timetable version")
    public ResponseEntity<ApiResponse<Void>> deleteVersion(
            @PathVariable Long timetableId) {
        commandBus.dispatch(new DeleteTimetableVersionCommand(timetableId));
        return ResponseEntity.ok(ApiResponse.noContent("Timetable version deleted"));
    }

    // ── Request body records ──────────────────────────────────────────────

    public record PublishTimetableRequest(
            String notes,
            @jakarta.validation.constraints.NotEmpty
            @Valid
            List<TimetableSlotInput> slots) {}
}
