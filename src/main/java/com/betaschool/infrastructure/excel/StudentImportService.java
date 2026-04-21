package com.betaschool.infrastructure.excel;

import com.betaschool.infrastructure.persistence.entity.StudentEntity;
import com.betaschool.infrastructure.persistence.repository.JpaStudentRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaAppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Processes the student import file.
 *
 * All valid rows are saved in a single saveAll() call at the end of the method.
 * Invalid rows are collected and returned without failing the whole batch.
 * The transaction is @Transactional so either all valid rows commit or nothing does.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentImportService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}$");

    private final JpaStudentRepository  studentRepo;
    private final JpaAppUserRepository  userRepo;

    /**
     * Validates the file, processes all rows, persists valid students,
     * and returns a full ImportResult including per-row errors.
     */
    @Transactional
    public ImportResult importStudents(MultipartFile file, Long schoolId) throws IOException {
        ExcelImportUtil.validateFile(file);

        List<Map<String, String>> rows = ExcelImportUtil.parseRows(
                file, "surname", "other names");

        ImportResult.Builder result = ImportResult.builder().totalRows(rows.size());

        // Pre-load existing emails to avoid N queries for uniqueness checking
        Set<String> existingDbEmails = studentRepo.findAllEmailsBySchoolId(schoolId);
        Set<String> seenInFile      = new HashSet<>();
        List<StudentEntity> toSave  = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2; // 1-indexed, row 1 is header
            Map<String, String> row = rows.get(i);

            String surname    = row.getOrDefault("surname",     "").trim();
            String otherNames = row.getOrDefault("other names", "").trim();
            String email      = row.getOrDefault("email",       "").trim().toLowerCase();
            if (email.isBlank()) email = null;

            // ── Per-row validation ─────────────────────────────────────────
            if (surname.isBlank()) {
                result.error(rowNum, "Surname is required");
                continue;
            }
            if (otherNames.isBlank()) {
                result.error(rowNum, "Other Names is required");
                continue;
            }
            if (email != null) {
                if (!EMAIL_PATTERN.matcher(email).matches()) {
                    result.error(rowNum, "Invalid email format: " + email);
                    continue;
                }
                if (seenInFile.contains(email)) {
                    result.error(rowNum, "Duplicate email within file: " + email);
                    continue;
                }
                if (existingDbEmails.contains(email)) {
                    result.error(rowNum, "Email already registered in this school: " + email);
                    continue;
                }
                if (userRepo.existsByEmail(email)) {
                    result.error(rowNum,
                            "Email '" + email + "' is already a login account. "
                            + "Leave email blank or use a different address.");
                    continue;
                }
                seenInFile.add(email);
            }

            toSave.add(StudentEntity.builder()
                    .surname(surname)
                    .otherNames(otherNames)
                    .email(email)
                    .schoolId(schoolId)
                    .build());
        }

        studentRepo.saveAll(toSave);

        int saved = toSave.size();
        int failed = rows.size() - saved;
        result.successCount(saved).failureCount(failed);

        log.info("Student import: school={} total={} saved={} failed={}",
                schoolId, rows.size(), saved, failed);
        return result.build();
    }

    /** Returns raw bytes of a downloadable .xlsx template for student import. */
    public byte[] buildTemplate() {
        return ExcelImportUtil.buildTemplate(
                new String[]{ "Surname", "Other Names", "Email" },
                new String[]{ "Okonkwo", "Chukwuemeka", "c.okonkwo@school.edu" });
    }
}
