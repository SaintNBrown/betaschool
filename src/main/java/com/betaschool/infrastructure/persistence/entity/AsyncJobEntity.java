package com.betaschool.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "async_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsyncJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false,
            columnDefinition = "uuid DEFAULT gen_random_uuid()")
    private UUID id;

    @Column(name = "school_id", nullable = false)
    private Long schoolId;

    @Column(name = "job_type", nullable = false, length = 50)
    private String jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "job_status")
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    private JobStatus status;

    @Column(name = "requested_by")
    private Long requestedBy;

    /**
     * JSONB column — stored as a plain String, serialised/deserialised by
     * AsyncJobService using Jackson. This avoids needing a custom Hibernate
     * AttributeConverter or the PostgreSQL JSONB type in the entity graph.
     */
    @Column(name = "params", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String params;

    @Column(name = "result_summary", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String resultSummary;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "progress_pct", nullable = false)
    @Builder.Default
    private int progressPct = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    public enum JobStatus {
        QUEUED, RUNNING, COMPLETED, FAILED
    }

    public enum JobType {
        BULK_REPORT_CARD;

        @Override
        public String toString() { return name(); }
    }
}
