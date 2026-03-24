package com.betaschool.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Continuous assessment / test score for a student in a specific examination.
 * One row per student per examination.
 * Combined score = test_score.score + result.score (exam component).
 */
@Entity
@Table(name = "test_score",
       uniqueConstraints = @UniqueConstraint(columnNames = {"examination_id", "student_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestScoreEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "examination_id", nullable = false)
    private ExaminationEntity examination;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private StudentEntity student;

    /** The student's test/CA score — must not exceed examination.testMaxScore. */
    @Column(name = "score", nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    /** Optional label e.g. "Test 1", "CA", "Mid-term test". */
    @Column(name = "notes", length = 200)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
