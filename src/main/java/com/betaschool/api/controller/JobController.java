package com.betaschool.api.controller;

import com.betaschool.api.dto.response.ApiResponse;
import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity.JobType;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.jobs.AsyncJobService;
import com.betaschool.jobs.AsyncJobService.AsyncJobDto;
import com.betaschool.jobs.AsyncJobService.FileEntry;
import com.betaschool.jobs.BulkReportCardJobService;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/jobs")
@RequiredArgsConstructor
@Tag(name = "Async Jobs", description = "Submit and track long-running bulk operations")
public class JobController {

    private final AsyncJobService         jobService;
    private final BulkReportCardJobService bulkService;
    private final TenantGuard             tenantGuard;
    private final JpaSchoolRepository     schoolRepo;

    // ── POST /jobs/bulk-report-cards ──────────────────────────────────────

    @PostMapping("/bulk-report-cards")
    @Operation(summary = "[SCHOOL_ADMIN] Start bulk report card generation for a class-term",
               description = """
                   Creates a QUEUED job immediately and returns its ID.
                   Processing runs in the background — poll GET /jobs/{jobId} for progress.
                   When status=COMPLETED, download all PDFs as a ZIP via GET /jobs/{jobId}/download.
                   """)
    public ResponseEntity<ApiResponse<Map<String, UUID>>> submitBulkReportCards(
            @Valid @RequestBody BulkReportCardRequest req) {

        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId     = tenantGuard.requireSchoolId();
        Long requestedBy  = TenantContext.getUserId();

        // Create the persistent job row synchronously — returns immediately
        UUID jobId = jobService.createJob(
                JobType.BULK_REPORT_CARD,
                Map.of("classSessionId", req.classSessionId(), "termId", req.termId()),
                schoolId,
                requestedBy);

        // Resolve school name for the PDF header — do this on the request thread
        // while we still have a DB connection and TenantContext
        String schoolName = schoolRepo.findById(schoolId)
                .map(s -> s.getName())
                .orElse("BetaSchool");

        // Launch async — this returns immediately; background thread does the work
        bulkService.runBulkReportCard(jobId, req.classSessionId(),
                req.termId(), schoolId, schoolName);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok("Job queued — poll for progress", Map.of("jobId", jobId)));
    }

    // ── GET /jobs/{jobId} ─────────────────────────────────────────────────

    @GetMapping("/{jobId}")
    @Operation(summary = "[SCHOOL_ADMIN] Get job status and progress")
    public ResponseEntity<ApiResponse<AsyncJobDto>> getJob(@PathVariable UUID jobId) {
        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId = tenantGuard.requireSchoolId();
        return ResponseEntity.ok(ApiResponse.ok(jobService.getJob(jobId, schoolId)));
    }

    // ── GET /jobs ─────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "[SCHOOL_ADMIN] List jobs for this school",
               description = "Filter by type= (e.g. BULK_REPORT_CARD) and/or status= (QUEUED, RUNNING, COMPLETED, FAILED)")
    public ResponseEntity<ApiResponse<List<AsyncJobDto>>> listJobs(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {

        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId = tenantGuard.requireSchoolId();
        return ResponseEntity.ok(ApiResponse.ok(jobService.listJobs(schoolId, type, status)));
    }

    // ── GET /jobs/{jobId}/download ────────────────────────────────────────

    @GetMapping("/{jobId}/download")
    @Operation(summary = "[SCHOOL_ADMIN] Download all PDFs from a COMPLETED job as a ZIP",
               description = """
                   Only available when job status=COMPLETED.
                   Returns a ZIP file containing one PDF per student.
                   Returns 400 if the job is still running or has failed.
                   """)
    public void downloadZip(@PathVariable UUID jobId,
                            HttpServletResponse response) throws IOException {

        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId = tenantGuard.requireSchoolId();

        List<FileEntry> files = jobService.getFilesForJob(jobId, schoolId);

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"report-cards-" + jobId + ".zip\"");

        // Stream directly into the response — no intermediate byte buffer
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (FileEntry file : files) {
                zos.putNextEntry(new ZipEntry(file.filename()));
                zos.write(file.content());
                zos.closeEntry();
            }
        }
    }

    // ── Request records ───────────────────────────────────────────────────

    public record BulkReportCardRequest(
            @NotNull Long classSessionId,
            @NotNull Long termId
    ) {}
}
