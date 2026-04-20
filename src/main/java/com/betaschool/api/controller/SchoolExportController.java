package com.betaschool.api.controller;

import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.SlotType;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * GET /schools/export/full
 *
 * Streams a ZIP of 10 CSV files directly to the HTTP response.
 * No intermediate byte buffer — safe for schools with 2000+ students.
 *
 * Every query uses JOIN FETCH to eliminate N+1. The exam_results sheet
 * is the most expensive: two queries (results + test scores), then an
 * in-memory map join — still O(N) not O(N²).
 *
 * The entire method is @Transactional(readOnly=true) so all lazy
 * associations in the entity graph can be traversed safely inside
 * the CSV writing loops without LazyInitializationException.
 */
@RestController
@RequestMapping("/schools")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "School Export", description = "Full data export for portability and offboarding")
public class SchoolExportController {

    private final TenantGuard                         tenantGuard;
    private final JpaSchoolRepository                 schoolRepo;
    private final JpaStudentRepository                studentRepo;
    private final JpaTeacherRepository                teacherRepo;
    private final JpaSessionRepository                sessionRepo;
    private final JpaClassRepository                  classRepo;
    private final JpaClassSessionRepository           classSessionRepo;
    private final JpaSubjectRepository                subjectRepo;
    private final JpaClassSubjectRepository           classSubjectRepo;
    private final JpaStudentClassEnrollmentRepository enrollmentRepo;
    private final JpaResultRepository                 resultRepo;
    private final JpaTestScoreRepository              testScoreRepo;
    private final JpaTimetableSlotRepository          timetableSlotRepo;

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()     // header is written manually per-entry
            .build();

    @GetMapping("/export/full")
    @Transactional(readOnly = true)
    @Operation(
        summary = "[SCHOOL_ADMIN] Export all school data as a ZIP of CSV files",
        description = """
            Returns a ZIP containing 10 CSV files covering every entity in the school:
            students, teachers, sessions, classes, class-sessions, subjects,
            class-subjects (with teacher), enrollments, exam results (CA + exam + grade),
            and active timetable slots.

            Safe for schools with 2000+ students — streamed directly to the response,
            no intermediate memory buffer. Includes header-only files when no data exists.
            """)
    public void exportFull(HttpServletResponse response) throws IOException {
        tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
        Long schoolId = tenantGuard.requireSchoolId();

        String slug = schoolRepo.findById(schoolId)
                .map(s -> s.getSlug())
                .orElse("school-" + schoolId);

        String filename = "betaschool-export-" + slug + "-"
                + LocalDate.now().format(DATE_FMT) + ".zip";

        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        response.setHeader("Cache-Control", "no-store");
        // Disable buffering — Tomcat/Undertow must not buffer the whole response
        response.setBufferSize(0);

        long start = System.currentTimeMillis();
        log.info("Starting full data export: school={} slug={}", schoolId, slug);

        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream())) {

            writeStudents(zip, schoolId);
            writeTeachers(zip, schoolId);
            writeSessions(zip, schoolId);
            writeClasses(zip, schoolId);
            writeClassSessions(zip, schoolId);
            writeSubjects(zip, schoolId);
            writeClassSubjects(zip, schoolId);
            writeEnrollments(zip, schoolId);
            writeExamResults(zip, schoolId);
            writeTimetables(zip, schoolId);

            zip.flush();
        }

        log.info("Export complete: school={} slug={} durationMs={}",
                schoolId, slug, System.currentTimeMillis() - start);
    }

    // ── 1. students.csv ───────────────────────────────────────────────────

    private void writeStudents(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "students.csv");
        try (CSVPrinter p = printer(zip, "id", "surname", "otherNames", "email")) {
            for (StudentEntity s : studentRepo.findBySchoolId(schoolId)) {
                p.printRecord(s.getId(), s.getSurname(), s.getOtherNames(), s.getEmail());
            }
        }
        zip.closeEntry();
    }

    // ── 2. teachers.csv ───────────────────────────────────────────────────

    private void writeTeachers(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "teachers.csv");
        try (CSVPrinter p = printer(zip, "id", "surname", "otherNames", "email")) {
            for (TeacherEntity t : teacherRepo.findBySchoolId(schoolId)) {
                p.printRecord(t.getId(), t.getSurname(), t.getOtherNames(), t.getEmail());
            }
        }
        zip.closeEntry();
    }

    // ── 3. sessions.csv ───────────────────────────────────────────────────

    private void writeSessions(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "sessions.csv");
        try (CSVPrinter p = printer(zip,
                "id", "sessionName", "startDate", "closingDate", "isCurrent")) {
            for (SessionEntity s : sessionRepo.findBySchoolId(schoolId)) {
                p.printRecord(
                        s.getId(), s.getSessionName(),
                        fmt(s.getStartDate()), fmt(s.getClosingDate()),
                        s.isCurrent());
            }
        }
        zip.closeEntry();
    }

    // ── 4. classes.csv ────────────────────────────────────────────────────

    private void writeClasses(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "classes.csv");
        try (CSVPrinter p = printer(zip, "id", "name")) {
            for (ClassEntity c : classRepo.findBySchoolId(schoolId)) {
                p.printRecord(c.getId(), c.getName());
            }
        }
        zip.closeEntry();
    }

    // ── 5. class_sessions.csv ─────────────────────────────────────────────

    private void writeClassSessions(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "class_sessions.csv");
        try (CSVPrinter p = printer(zip,
                "id", "sessionId", "sessionName", "classId", "className")) {
            for (ClassSessionEntity cs
                    : classSessionRepo.findAllBySchoolIdWithDetails(schoolId)) {
                p.printRecord(
                        cs.getId(),
                        cs.getSession().getId(),  cs.getSession().getSessionName(),
                        cs.getClazz().getId(),    cs.getClazz().getName());
            }
        }
        zip.closeEntry();
    }

    // ── 6. subjects.csv ───────────────────────────────────────────────────

    private void writeSubjects(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "subjects.csv");
        try (CSVPrinter p = printer(zip, "id", "name")) {
            for (SubjectEntity s : subjectRepo.findBySchoolId(schoolId)) {
                p.printRecord(s.getId(), s.getName());
            }
        }
        zip.closeEntry();
    }

    // ── 7. class_subjects.csv ─────────────────────────────────────────────

    private void writeClassSubjects(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "class_subjects.csv");
        try (CSVPrinter p = printer(zip,
                "id", "classSessionId", "subjectName", "isElective", "teacherName")) {
            for (ClassSubjectEntity cs
                    : classSubjectRepo.findAllForExport(schoolId)) {
                String teacherName = "";
                if (cs.getTeacherAssignment() != null
                        && cs.getTeacherAssignment().getTeacher() != null) {
                    TeacherEntity t = cs.getTeacherAssignment().getTeacher();
                    teacherName = t.getSurname() + " " + t.getOtherNames();
                }
                p.printRecord(
                        cs.getId(),
                        cs.getClassSession().getId(),
                        cs.getSubject().getName(),
                        cs.isElective(),
                        teacherName);
            }
        }
        zip.closeEntry();
    }

    // ── 8. enrollments.csv ────────────────────────────────────────────────

    private void writeEnrollments(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "enrollments.csv");
        try (CSVPrinter p = printer(zip,
                "studentId", "studentName", "classSessionId", "className", "sessionName")) {
            for (StudentClassEnrollmentEntity e
                    : enrollmentRepo.findAllForExport(schoolId)) {
                StudentEntity st = e.getStudent();
                ClassSessionEntity cs = e.getClassSession();
                p.printRecord(
                        st.getId(),
                        st.getSurname() + " " + st.getOtherNames(),
                        cs.getId(),
                        cs.getClazz().getName(),
                        cs.getSession().getSessionName());
            }
        }
        zip.closeEntry();
    }

    // ── 9. exam_results.csv ───────────────────────────────────────────────
    // Two queries: one for all ResultEntity rows (exam scores), one for all
    // TestScoreEntity rows (CA scores). Build a lookup map from the second,
    // then join in memory when writing. O(N) total — no N+1.

    private void writeExamResults(ZipOutputStream zip, Long schoolId) throws IOException {
        // Query 2: load all CA scores into a map keyed by (examinationId, studentId)
        record ScoreKey(Long examId, Long studentId) {}
        Map<ScoreKey, BigDecimal> caScores = new HashMap<>();
        for (TestScoreEntity ts : testScoreRepo.findAllForExport(schoolId)) {
            caScores.put(
                    new ScoreKey(ts.getExamination().getId(), ts.getStudent().getId()),
                    ts.getScore());
        }

        startEntry(zip, "exam_results.csv");
        try (CSVPrinter p = printer(zip,
                "examinationId", "subjectName", "termNumber",
                "className",     "sessionName",
                "studentId",     "studentName",
                "examScore",     "testScore", "combinedScore", "grade")) {

            // Query 1: all exam results with full chain
            for (ResultEntity r : resultRepo.findAllForExport(schoolId)) {
                ExaminationEntity exam = r.getExamination();
                TermEntity        term = exam.getTerm();
                ClassSessionEntity cs = term.getClassSession();
                StudentEntity      st = r.getStudent();

                BigDecimal examScore = r.getScore();
                BigDecimal testScore = caScores.get(
                        new ScoreKey(exam.getId(), st.getId()));
                BigDecimal combined = (examScore != null ? examScore : BigDecimal.ZERO)
                        .add(testScore != null ? testScore : BigDecimal.ZERO);

                p.printRecord(
                        exam.getId(),
                        exam.getClassSubject().getSubject().getName(),
                        term.getTermNumber(),
                        cs.getClazz().getName(),
                        cs.getSession().getSessionName(),
                        st.getId(),
                        st.getSurname() + " " + st.getOtherNames(),
                        examScore,
                        testScore,
                        combined.compareTo(BigDecimal.ZERO) > 0 ? combined : null,
                        r.getGrade());
            }
        }
        zip.closeEntry();
    }

    // ── 10. timetables.csv ────────────────────────────────────────────────

    private void writeTimetables(ZipOutputStream zip, Long schoolId) throws IOException {
        startEntry(zip, "timetables.csv");
        try (CSVPrinter p = printer(zip,
                "classSessionId", "className", "version",
                "dayOfWeek",      "startTime", "endTime",
                "subjectName",    "slotType")) {
            for (TimetableSlotEntity slot
                    : timetableSlotRepo.findAllActiveForExport(schoolId)) {
                TimetableEntity    tt = slot.getTimetable();
                ClassSessionEntity cs = tt.getClassSession();

                String subjectName = "";
                if (slot.getSlotType() == SlotType.SUBJECT
                        && slot.getClassSubject() != null
                        && slot.getClassSubject().getSubject() != null) {
                    subjectName = slot.getClassSubject().getSubject().getName();
                } else if (slot.getActivityLabel() != null) {
                    subjectName = slot.getActivityLabel();
                }

                p.printRecord(
                        cs.getId(),
                        cs.getClazz().getName(),
                        tt.getVersion(),
                        slot.getDayOfWeek().name(),
                        slot.getStartTime(),
                        slot.getEndTime(),
                        subjectName,
                        slot.getSlotType().name());
            }
        }
        zip.closeEntry();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Opens a new ZIP entry. ZipOutputStream.putNextEntry() flushes and closes
     * the previous entry automatically, so no explicit closeEntry() is needed
     * before calling this — but we call closeEntry() explicitly after each writer
     * is closed for clarity.
     */
    private static void startEntry(ZipOutputStream zip, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
    }

    /**
     * Creates a CSVPrinter that writes into the current ZIP entry.
     *
     * Key constraint: the Writer must NOT be closed after use (that would close the
     * underlying ZipOutputStream). We use a non-closing wrapper. The CSVPrinter's
     * try-with-resources calls flush() on close, which is safe.
     *
     * The header row is written immediately.
     */
    private static CSVPrinter printer(ZipOutputStream zip, String... headers)
            throws IOException {
        // Wrap ZipOutputStream in a Writer that ignores close()
        Writer writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8) {
            @Override public void close() throws IOException { flush(); /* do NOT close zip */ }
        };
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader(headers)
                .build();
        return new CSVPrinter(writer, format);
    }

    private static String fmt(LocalDate date) {
        return date != null ? date.format(DATE_FMT) : "";
    }
}
