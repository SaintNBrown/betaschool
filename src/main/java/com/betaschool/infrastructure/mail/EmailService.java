package com.betaschool.infrastructure.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;
import java.util.Map;

/**
 * Email service interface with two conditional implementations:
 *
 *  LiveEmailService   — registered only when JavaMailSender bean is present
 *                       (i.e. spring.mail.username is configured in the environment).
 *                       Sends real SMTP HTML emails.
 *
 *  NoOpEmailService   — registered when JavaMailSender is NOT present
 *                       (no SMTP credentials). Logs the reset link so the app
 *                       starts and is fully usable in dev/CI without a mail server.
 *
 * The ForgotPasswordHandler injects this interface — it works in both modes.
 */
public interface EmailService {

    void sendPasswordResetEmail(String toEmail, String schoolName,
                                String resetLink, int expiryMins);

    /**
     * Sent immediately after a school self-registers.
     * Confirms their credentials and links them straight to the login page.
     */
    void sendWelcomeEmail(String toAdminEmail, String adminName,
                          String schoolName, String loginUrl);

    // ── Live SMTP implementation (active when JavaMailSender bean exists) ─

    @Component
    @ConditionalOnBean(JavaMailSender.class)
    @Slf4j
    class LiveEmailService implements EmailService {

        private final JavaMailSender mailSender;
        private final TemplateEngine templateEngine;
        private final String fromAddress;
        private final String fromName;

        @Autowired
        public LiveEmailService(JavaMailSender mailSender,
                                TemplateEngine templateEngine,
                                @Value("${app.mail.from-address}") String fromAddress,
                                @Value("${app.mail.from-name}") String fromName) {
            this.mailSender     = mailSender;
            this.templateEngine = templateEngine;
            this.fromAddress    = fromAddress;
            this.fromName       = fromName;
            log.info("Email service: LIVE mode (SMTP via {})", fromAddress);
        }

        @Override
        @Async
        public void sendWelcomeEmail(String toAdminEmail, String adminName,
                                     String schoolName, String loginUrl) {
            Context ctx = new Context(Locale.ENGLISH);
            ctx.setVariables(Map.of(
                    "adminName",  adminName,
                    "schoolName", schoolName,
                    "loginUrl",   loginUrl,
                    "email",      toAdminEmail));
            send(toAdminEmail, "Welcome to BetaSchool — " + schoolName + " is ready",
                    "email/welcome-school", ctx);
        }

        @Override
        @Async
        public void sendPasswordResetEmail(String toEmail, String schoolName,
                                           String resetLink, int expiryMins) {
            Context ctx = new Context(Locale.ENGLISH);
            ctx.setVariables(Map.of(
                    "schoolName", schoolName,
                    "resetLink",  resetLink,
                    "expiryMins", expiryMins,
                    "email",      toEmail));
            send(toEmail, "Reset your " + schoolName + " password",
                    "email/password-reset", ctx);
        }

        private void send(String to, String subject, String templateName, Context ctx) {
            try {
                String html = templateEngine.process(templateName, ctx);
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromAddress, fromName);
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                mailSender.send(message);
                log.info("Email sent: to={} subject={}", to, subject);
            } catch (MessagingException | java.io.UnsupportedEncodingException e) {
                // Log but don't propagate — token is already persisted;
                // the user can re-request a reset link.
                log.error("Failed to send email to {}: {}", to, e.getMessage());
            }
        }
    }

    // ── No-op fallback (active when JavaMailSender bean is NOT present) ───

    @Component
    @ConditionalOnMissingBean(JavaMailSender.class)
    @Slf4j
    class NoOpEmailService implements EmailService {

        public NoOpEmailService() {
            log.warn("Email service: NO-OP mode — MAIL_USERNAME not configured. "
                    + "Password reset links will be logged to stdout only. "
                    + "Set MAIL_HOST, MAIL_USERNAME, MAIL_PASSWORD in production.");
        }

        @Override
        public void sendWelcomeEmail(String toAdminEmail, String adminName,
                                     String schoolName, String loginUrl) {
            log.warn("[NO-OP EMAIL] Welcome email for {} ({}) | login: {}",
                    schoolName, toAdminEmail, loginUrl);
        }

        @Override
        public void sendPasswordResetEmail(String toEmail, String schoolName,
                                           String resetLink, int expiryMins) {
            log.warn("[NO-OP EMAIL] Password reset for {} | valid {}min | link: {}",
                    toEmail, expiryMins, resetLink);
        }
    }
}