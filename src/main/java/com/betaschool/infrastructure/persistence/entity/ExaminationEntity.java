package com.betaschool.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "examination",
       uniqueConstraints = @UniqueConstraint(columnNames = {"term_id", "class_subject_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExaminationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "term_id", nullable = false)
    private TermEntity term;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "class_subject_id", nullable = false)
    private ClassSubjectEntity classSubject;

    @Column(name = "exam_date")
    private LocalDate examDate;

    /** Time the examination starts on exam_date. */
    @Column(name = "exam_start_time")
    private LocalTime examStartTime;

    /** Duration of this examination in minutes. */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /**
     * Score weight configuration. testMaxScore + examMaxScore must equal 100.
     * Default: tests carry 40 marks, exam carries 60 marks.
     */
    @Column(name = "test_max_score", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal testMaxScore = BigDecimal.valueOf(40);

    @Column(name = "exam_max_score", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal examMaxScore = BigDecimal.valueOf(60);

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "examination", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ResultEntity> results = new ArrayList<>();

    @OneToMany(mappedBy = "examination", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<TestScoreEntity> testScores = new ArrayList<>();
}
