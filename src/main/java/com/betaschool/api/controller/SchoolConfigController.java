package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.command.model.SchoolConfigCommand.SetGradingScaleCommand;
import com.betaschool.command.model.SchoolConfigCommand.SetReportCardDisplayCommand;
import com.betaschool.command.model.SchoolConfigCommand.SetScoreRatioCommand;
import com.betaschool.query.model.SchoolConfigQuery.GetSchoolScoreConfigQuery;
import com.betaschool.query.model.SchoolConfigQueryResult.SchoolScoreConfig;
import com.betaschool.shared.CommandBus;
import com.betaschool.shared.QueryBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/schools/config")
@RequiredArgsConstructor
@Tag(name = "School Configuration", description = "Per-school score ratio and grading scale settings")
public class SchoolConfigController {

    private final CommandBus commandBus;
    private final QueryBus   queryBus;

    // ── GET current config ────────────────────────────────────────────────

    @GetMapping("/score")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','SYSTEM_ADMIN','TEACHER','STUDENT')")
    @Operation(summary = "Get this school's current score ratio and grading scale")
    public ResponseEntity<ApiResponse<SchoolScoreConfig>> getConfig() {
        return ResponseEntity.ok(ApiResponse.ok(queryBus.dispatch(new GetSchoolScoreConfigQuery())));
    }

    // ── Set CA : Exam ratio ───────────────────────────────────────────────

    @PutMapping("/score/ratio")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','SYSTEM_ADMIN')")
    @Operation(summary = "[SCHOOL_ADMIN] Set the CA-to-exam score ratio",
               description = "caWeight + examWeight must equal 100. " +
                             "Example: { \"caWeight\": 40, \"examWeight\": 60 } → 40:60 split. " +
                             "This ratio is used when creating new examinations as the default " +
                             "testMaxScore / examMaxScore split.")
    public ResponseEntity<ApiResponse<Void>> setRatio(@Valid @RequestBody SetRatioRequest req) {
        commandBus.dispatch(new SetScoreRatioCommand(req.caWeight(), req.examWeight()));
        return ResponseEntity.ok(ApiResponse.noContent(
                "Score ratio updated to " + req.caWeight() + ":" + req.examWeight()));
    }

    // ── Set grading scale ─────────────────────────────────────────────────

    @PutMapping("/score/grading")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','SYSTEM_ADMIN')")
    @Operation(summary = "[SCHOOL_ADMIN] Replace the school's grading scale",
               description = """
                       Replaces all grading bands for this school.
                       Rules:
                       - Bands must collectively cover 0–100 with no gaps and no overlaps.
                       - minScore must be <= maxScore for each band.
                       - Grade labels must be unique (case-insensitive).
                       - Scores are based on the combined CA + exam total out of 100.

                       Example body:
                       {
                         "bands": [
                           { "grade": "A",  "minScore": 75, "maxScore": 100 },
                           { "grade": "B",  "minScore": 65, "maxScore": 74  },
                           { "grade": "C",  "minScore": 55, "maxScore": 64  },
                           { "grade": "D",  "minScore": 45, "maxScore": 54  },
                           { "grade": "F",  "minScore": 0,  "maxScore": 44  }
                         ]
                       }
                       """)
    public ResponseEntity<ApiResponse<Void>> setGradingScale(
            @Valid @RequestBody SetGradingScaleRequest req) {
        List<SetGradingScaleCommand.GradingBandRequest> bands = req.bands().stream()
                .map(b -> new SetGradingScaleCommand.GradingBandRequest(b.grade(), b.minScore(), b.maxScore()))
                .toList();
        commandBus.dispatch(new SetGradingScaleCommand(bands));
        return ResponseEntity.ok(ApiResponse.noContent("Grading scale updated with " + bands.size() + " bands"));
    }

    // ── Set report card display preference ────────────────────────────────

    @PutMapping("/score/display")
    @PreAuthorize("hasAnyRole('SCHOOL_ADMIN','SYSTEM_ADMIN')")
    @Operation(summary = "[SCHOOL_ADMIN] Set what the report card shows alongside a student's average",
               description = """
                       Controls the summary section of the report card:

                       showStudentPosition = true  (default)
                           → Displays the student's class rank (position) AND average score.
                             e.g. "Position: 3rd | Average: 78.50"
                             Suitable for competitive grading environments.

                       showStudentPosition = false
                           → Displays the student's term average percentage AND a term grade
                             derived from the school's grading bands. No rank is shown.
                             e.g. "Average: 78.50% | Term Grade: B"
                             Suitable for holistic / non-competitive environments
                             (common in primary schools).

                       The term grade is computed by passing the student's average score
                       through the school's configured grading bands. For example, if the
                       average is 98% and the school's A-band is 75–100, the term grade is A.
                       """)
    public ResponseEntity<ApiResponse<Void>> setReportCardDisplay(
            @Valid @RequestBody SetDisplayRequest req) {
        commandBus.dispatch(new SetReportCardDisplayCommand(req.showStudentPosition()));
        return ResponseEntity.ok(ApiResponse.noContent(
                "Report card display updated: " +
                (req.showStudentPosition() ? "showing class position" : "showing term grade")));
    }

    // ── Request records ───────────────────────────────────────────────────

    public record SetRatioRequest(
            @NotNull @Min(0) @Max(100) Integer caWeight,
            @NotNull @Min(0) @Max(100) Integer examWeight
    ) {}

    public record SetGradingScaleRequest(
            @NotNull @Size(min = 1) @Valid List<BandRequest> bands
    ) {
        public record BandRequest(
                @NotBlank String grade,
                @NotNull @Min(0) @Max(100) Integer minScore,
                @NotNull @Min(0) @Max(100) Integer maxScore
        ) {}
    }

    public record SetDisplayRequest(
            @NotNull Boolean showStudentPosition
    ) {}
}
