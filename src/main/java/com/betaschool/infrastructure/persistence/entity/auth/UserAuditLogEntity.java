package com.betaschool.infrastructure.persistence.entity.auth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Audit trail for user-level actions: login, password change, deactivation, etc.
 * Maps to the user_audit_log table created in V3__audit_log.sql.
 */
@Entity
@Table(name = "user_audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "school_id")
    private Long schoolId;   // null for SYSTEM_ADMIN

    @Column(name = "action", nullable = false, length = 50)
    private String action;   // LOGIN, PASSWORD_CHANGE, DEACTIVATED, REACTIVATED

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
