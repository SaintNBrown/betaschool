package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public interface JpaPasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    /** Delete any existing token for this user before issuing a new one (one-at-a-time policy). */
    @Modifying
    @Query("DELETE FROM PasswordResetTokenEntity t WHERE t.user.id = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** Scheduled / maintenance cleanup — remove all expired tokens. */
    @Modifying
    @Query("DELETE FROM PasswordResetTokenEntity t WHERE t.expiresAt < :now")
    void deleteExpiredBefore(@Param("now") OffsetDateTime now);
}
