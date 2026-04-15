package com.betaschool.jobs;

import com.betaschool.infrastructure.pdf.ReportCardPdfGenerator;
import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity;
import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity.JobStatus;
import com.betaschool.infrastructure.persistence.entity.AsyncJobFileEntity;
import com.betaschool.infrastructure.persistence.repository.JpaAsyncJobFileRepository;
import com.betaschool.infrastructure.persistence.repository.JpaAsyncJobRepository;
import com.betaschool.infrastructure.persistence.repository.JpaStudentClassEnrollmentRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.query.model.StudentQuery.GetStudentReportCardQuery;
import com.betaschool.query.model.StudentQueryResult.ReportCard;
import com.betaschool.shared.QueryBus;
import com.betaschool.tenant.context.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes the BULK_REPORT_CARD async job.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  CRITICAL: @Async runs on a Spring thread-pool thread.                  │
 * │  TenantContext is ThreadLocal — it is NOT inherited by async threads     │
 * │  even with InheritableThreadLocal, because Spring's TaskExecutor clones │
 * │  threads from its pool, not from the request thread.                     │
 * │                                                                          │
 * │  Solution: caller passes schoolId, termId, classSessionId explicitly.   │
 * │  This method sets TenantContext manually before any QueryBus dispatch    │
 * │  and clears it in a finally block.                                       │
 * └─────────────────────────────────────────────────────────────────────────┘
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BulkReportCardJobService {

    private final JpaAsyncJobRepository        jobRepo;
    private final JpaAsyncJobFileRepository    fileRepo;
    private final JpaStudentClassEnrollmentRepository enrollRepo;
    private final JpaSchoolRepository          schoolRepo;
    private final ReportCardPdfGenerator       pdfGenerator;
    private final QueryBus                     queryBus;
    private final ObjectMapper                 objectMapper;

    /**
     * Entry point — called by JobController after the QUEUED row is persisted.
     *
     * @param jobId          the UUID of the already-created async_job row
     * @param classSessionId the class-session to process
     * @param termId         the term to generate report cards for
     * @param schoolId       explicit tenant ID — propagated from the HTTP request thread
     * @param schoolName     display name used in PDF header
     */
    @Async("jobExecutor")
    public void runBulkReportCard(UUID jobId, Long classSessionId,
                                  Long termId, Long schoolId, String schoolName) {

        // ── Restore tenant context on this async thread ───────────────────
        // The request thread's TenantContext is gone. Set schoolId and a
        // synthetic SCHOOL_ADMIN role so TenantGuard and SchoolIdInjector work.
        TenantContext.set(schoolId, null, "SCHOOL_ADMIN", null, null);

        try {
            executeJob(jobId, classSessionId, termId, schoolId, schoolName);
        } finally {
            // Always clear — async threads return to the pool after this method.
            TenantContext.clear();
        }
    }

    // ── Core execution ────────────────────────────────────────────────────

    private void executeJob(UUID jobId, Long classSessionId,
                            Long termId, Long schoolId, String schoolName) {

        AsyncJobEntity job = markRunning(jobId);
        if (job == null) return; // job was deleted concurrently — nothing to do

        // Fetch all enrolled student IDs for this class-session in a single query
        List<Long> studentIds = enrollRepo
                .findByClassSessionIdAndSchoolId(classSessionId, schoolId)
                .stream()
                .map(e -> e.getStudent().getId())
                .toList();

        if (studentIds.isEmpty()) {
            markCompleted(jobId, 0, 0);
            log.info("Bulk report card job {} completed — no students enrolled in classSession={}",
                    jobId, classSessionId);
            return;
        }

        int total     = studentIds.size();
        int generated = 0;
        int failed    = 0;
        List<String> failedNames = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            Long studentId = studentIds.get(i);

            try {
                // ── Generate report card data ─────────────────────────────
                ReportCard reportCard = queryBus.dispatch(
                        new GetStudentReportCardQuery(studentId, termId));

                // ── Generate PDF bytes ────────────────────────────────────
                byte[] pdfBytes = pdfGenerator.generate(reportCard, schoolName);

                // ── Persist file row ──────────────────────────────────────
                String filename = sanitiseFilename(reportCard.studentName())
                        + "_Term" + reportCard.termNumber() + ".pdf";
                saveFile(jobId, filename, pdfBytes);

                generated++;

            } catch (Exception ex) {
                failed++;
                failedNames.add("studentId=" + studentId + ": " + ex.getMessage());
                log.warn("Bulk report card: failed for student {} in job {}: {}",
                        studentId, jobId, ex.getMessage());
            }

            // Update progress after every student — cheap compared to PDF generation
            int pct = (int) Math.round((i + 1) * 100.0 / total);
            updateProgress(jobId, pct);
        }

        markCompleted(jobId, generated, failed);

        log.info("Bulk report card job {} finished: generated={} failed={} school={}",
                jobId, generated, failed, schoolId);

        if (!failedNames.isEmpty()) {
            log.warn("Failed students in job {}: {}", jobId, failedNames);
        }
    }

    // ── State transition helpers — each runs in its own short transaction ─

    /**
     * Transitions QUEUED → RUNNING atomically.
     * Uses REQUIRES_NEW so the update commits immediately and is visible
     * to the job status polling endpoint, regardless of the outer async transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected AsyncJobEntity markRunning(UUID jobId) {
        return jobRepo.findById(jobId).map(job -> {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(OffsetDateTime.now());
            return jobRepo.save(job);
        }).orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void updateProgress(UUID jobId, int pct) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setProgressPct(pct);
            jobRepo.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void saveFile(UUID jobId, String filename, byte[] content) {
        fileRepo.save(AsyncJobFileEntity.builder()
                .jobId(jobId)
                .filename(filename)
                .content(content)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markCompleted(UUID jobId, int generated, int failed) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.COMPLETED);
            job.setProgressPct(100);
            job.setCompletedAt(OffsetDateTime.now());
            try {
                job.setResultSummary(objectMapper.writeValueAsString(
                        Map.of("generated", generated, "failed", failed)));
            } catch (Exception ignored) {}
            jobRepo.save(job);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailed(UUID jobId, String errorMessage) {
        jobRepo.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.FAILED);
            job.setCompletedAt(OffsetDateTime.now());
            job.setErrorMessage(errorMessage);
            jobRepo.save(job);
        });
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String sanitiseFilename(String name) {
        if (name == null) return "Student";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
    }
}
