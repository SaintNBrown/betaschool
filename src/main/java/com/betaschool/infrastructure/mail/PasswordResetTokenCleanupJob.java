package com.betaschool.infrastructure.mail;

import com.betaschool.infrastructure.persistence.repository.auth.JpaPasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Removes expired, used, and stale password-reset tokens nightly.
 * Keeps the table small and prevents token accumulation.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordResetTokenCleanupJob {

    private final JpaPasswordResetTokenRepository tokenRepo;

    /** Runs every night at 02:00 server time. */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupExpiredTokens() {
        tokenRepo.deleteExpiredBefore(OffsetDateTime.now());
        log.info("Password reset token cleanup completed");
    }
}
