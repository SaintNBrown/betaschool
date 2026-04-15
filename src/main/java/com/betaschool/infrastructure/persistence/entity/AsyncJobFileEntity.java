package com.betaschool.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "async_job_file")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AsyncJobFileEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    /**
     * Raw file bytes. For a small school (200 students × ~50 KB per PDF = ~10 MB)
     * this fits comfortably in PostgreSQL BYTEA. Replace with S3 key for larger scale.
     */
    @Lob
    @Column(name = "content", nullable = false)
    private byte[] content;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
