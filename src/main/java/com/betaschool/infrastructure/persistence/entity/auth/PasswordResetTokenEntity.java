package com.betaschool.infrastructure.persistence.entity.auth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Stores a hashed, single-use password-reset token for one user.
 * Only one active token per user is allowed (enforced by UNIQUE on user_id).
 *
 * Security design:
 *  - The raw random token (URL-safe base64, 32 bytes = 256 bits) is generated
 *    in the service layer and sent by email only.
 *  - Only the SHA-256 hex hash of that token is stored here.
 *  - On reset, the submitted token is hashed and compared — the raw value
 *    never touches the database.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user this token belongs to. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUserEntity user;

    /** SHA-256 hex of the raw URL-safe token sent in the email. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /** When this token expires (default: 30 minutes from creation). */
    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /** True once this token has been successfully used. */
    @Column(name = "used", nullable = false)
    @Builder.Default
    private boolean used = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !used && !isExpired();
    }
}
