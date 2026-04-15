package com.betaschool.jobs;

import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity;
import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity.JobStatus;
import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity.JobType;
import com.betaschool.infrastructure.persistence.repository.JpaAsyncJobFileRepository;
import com.betaschool.infrastructure.persistence.repository.JpaAsyncJobRepository;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates, reads, and lists async jobs.
 *
 * Deliberately does NOT contain any @Async logic — that lives in BulkReportCardJobService.
 * This separation means AsyncJobService can be injected anywhere without triggering
 * Spring's proxy limitation ("@Async on the same bean doesn't work").
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AsyncJobService {

    private final JpaAsyncJobRepository jobRepo;
    private final JpaAsyncJobFileRepository fileRepo;
    private final ObjectMapper objectMapper;

    // ── Job creation ──────────────────────────────────────────────────────

    @Transactional
    public UUID createJob(JobType type, Map<String, Object> params,
                          Long schoolId, Long requestedBy) {
        String paramsJson;
        try {
            paramsJson = objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise job params", e);
        }

        AsyncJobEntity job = AsyncJobEntity.builder()
                .schoolId(schoolId)
                .jobType(type.name())
                .status(JobStatus.QUEUED)
                .requestedBy(requestedBy)
                .params(paramsJson)
                .progressPct(0)
                .build();

        return jobRepo.save(job).getId();
    }

    // ── Job status read ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AsyncJobDto getJob(UUID jobId, Long schoolId) {
        AsyncJobEntity job = jobRepo.findByIdAndSchoolId(jobId, schoolId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Job not found or does not belong to your school: " + jobId));
        return toDto(job);
    }

    // ── Job listing ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AsyncJobDto> listJobs(Long schoolId, String type, String status) {
        JobStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try { statusEnum = JobStatus.valueOf(status.toUpperCase()); }
            catch (IllegalArgumentException e) { /* unknown status → treat as null filter */ }
        }
        return jobRepo.findBySchoolIdAndFilters(schoolId, type, statusEnum)
                .stream()
                .map(this::toDto)
                .toList();
    }

    // ── File access ───────────────────────────────────────────────────────

    /**
     * Returns the raw bytes of every file associated with a COMPLETED job.
     * The caller (JobController.download) zips these into a single response stream.
     */
    @Transactional(readOnly = true)
    public List<FileEntry> getFilesForJob(UUID jobId, Long schoolId) {
        AsyncJobEntity job = jobRepo.findByIdAndSchoolId(jobId, schoolId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Job not found: " + jobId));

        if (job.getStatus() != JobStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not yet COMPLETED — current status: " + job.getStatus());
        }

        return fileRepo.findByJobId(jobId).stream()
                .map(f -> new FileEntry(f.getFilename(), f.getContent()))
                .toList();
    }

    // ── DTO / record types ────────────────────────────────────────────────

    public record AsyncJobDto(
            UUID id,
            String jobType,
            String status,
            int progressPct,
            String params,
            String resultSummary,
            String errorMessage,
            java.time.OffsetDateTime createdAt,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime completedAt
    ) {}

    public record FileEntry(String filename, byte[] content) {}

    // ── Internal helper ───────────────────────────────────────────────────

    private AsyncJobDto toDto(AsyncJobEntity j) {
        return new AsyncJobDto(
                j.getId(), j.getJobType(),
                j.getStatus() != null ? j.getStatus().name() : null,
                j.getProgressPct(),
                j.getParams(), j.getResultSummary(), j.getErrorMessage(),
                j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt());
    }
}
