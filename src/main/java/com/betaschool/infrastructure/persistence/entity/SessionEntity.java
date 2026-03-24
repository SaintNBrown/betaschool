package com.betaschool.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "school_id", nullable = false)
    private Long schoolId;


    @Column(name = "session_name", nullable = false, unique = true, length = 50)
    private String sessionName;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "closing_date", nullable = false)
    private LocalDate closingDate;

    /**
     * Issue 5: Marks this as the school's active/current academic session.
     * At most one session per school may be current at a time — enforced by
     * a partial unique index in V5 migration and by SetCurrentSessionHandler.
     */
    @Column(name = "is_current", nullable = false)
    @Builder.Default
    private boolean current = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<ClassSessionEntity> classSessions = new ArrayList<>();
}
