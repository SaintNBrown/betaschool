package com.betaschool.query.model;

import java.util.List;

public class SchoolConfigQueryResult {

    /**
     * Full score configuration for a school.
     * Returned by GET /schools/config/score.
     */
    public record SchoolScoreConfig(
            Long   schoolId,
            int    caWeight,
            int    examWeight,
            String ratioLabel,          // e.g. "40:60"
            List<GradingBandView> gradingBands
    ) {}

    public record GradingBandView(
            String grade,
            int    minScore,
            int    maxScore,
            String rangeLabel           // e.g. "70 - 100"
    ) {}
}
