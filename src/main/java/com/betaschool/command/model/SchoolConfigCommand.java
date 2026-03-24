package com.betaschool.command.model;

import com.betaschool.shared.Command;
import jakarta.validation.constraints.*;
import jakarta.validation.Valid;

import java.util.List;

public sealed interface SchoolConfigCommand {

    /**
     * Set the CA-to-exam score ratio for the school.
     * caWeight + examWeight must equal 100.
     * Example: caWeight=40, examWeight=60 → 40:60 split.
     */
    record SetScoreRatioCommand(
            @NotNull @Min(0) @Max(100) Integer caWeight,
            @NotNull @Min(0) @Max(100) Integer examWeight
    ) implements Command<Void>, SchoolConfigCommand {}

    /**
     * Replace the school's grading bands entirely.
     * Rules enforced by handler:
     *  - At least 1 band required.
     *  - minScore <= maxScore for each band.
     *  - Bands must collectively cover 0–100 with no gaps or overlaps.
     *  - Grade labels must be unique.
     */
    record SetGradingScaleCommand(
            @NotEmpty @Valid List<GradingBandRequest> bands
    ) implements Command<Void>, SchoolConfigCommand {

        public record GradingBandRequest(
                @NotBlank String grade,
                @NotNull @Min(0) @Max(100) Integer minScore,
                @NotNull @Min(0) @Max(100) Integer maxScore
        ) {}
    }
}
