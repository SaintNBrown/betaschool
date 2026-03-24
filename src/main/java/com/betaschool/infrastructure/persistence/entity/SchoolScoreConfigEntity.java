package com.betaschool.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Per-school configuration for:
 *  - CA (test) vs Examination score ratio  (caWeight + examWeight == 100)
 *  - Grading bands  (e.g. A: 70–100, B: 60–69 …)
 *
 * One row per school; created automatically when a school is registered
 * (seeded in V9 migration for existing schools).
 */
@Entity
@Table(name = "school_score_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchoolScoreConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false, unique = true)
    private Long schoolId;

    /** CA (continuous assessment) weight out of 100. Default 40. */
    @Column(name = "ca_weight", nullable = false)
    @Builder.Default
    private Integer caWeight = 40;

    /** Examination weight out of 100. Default 60. */
    @Column(name = "exam_weight", nullable = false)
    @Builder.Default
    private Integer examWeight = 60;

    /**
     * Ordered list of grading bands stored as JSONB in Postgres.
     * Each band: { "grade": "A", "minScore": 70, "maxScore": 100 }
     * Bands must be contiguous and cover 0–100; enforced at application layer.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "grading_bands", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<GradingBand> gradingBands = defaultBands();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    // ── Embedded value object ─────────────────────────────────────────────

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GradingBand {
        private String  grade;
        private Integer minScore;
        private Integer maxScore;
    }

    // ── Convenience ───────────────────────────────────────────────────────

    public static List<GradingBand> defaultBands() {
        return List.of(
            new GradingBand("A", 70, 100),
            new GradingBand("B", 60, 69),
            new GradingBand("C", 50, 59),
            new GradingBand("D", 45, 49),
            new GradingBand("F",  0, 44)
        );
    }

    /**
     * Resolve the grade letter for a combined score using this school's bands.
     * Falls back to "F" if no band matches.
     */
    public String resolveGrade(int combinedScore) {
        for (GradingBand band : gradingBands) {
            if (combinedScore >= band.getMinScore() && combinedScore <= band.getMaxScore()) {
                return band.getGrade();
            }
        }
        return "F";
    }
}
