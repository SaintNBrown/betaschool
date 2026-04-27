package com.betaschool.infrastructure.excel;

import com.betaschool.infrastructure.persistence.entity.StudentEntity;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity.UserRole;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity.UserStatus;
import com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity.ProfileType;
import com.betaschool.infrastructure.persistence.repository.JpaStudentRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaAppUserRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaUserProfileRepository;
import com.betaschool.shared.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Processes the student bulk import file.
 *
 * Two modes controlled by the createAccounts flag:
 *
 *   false (default) — creates StudentEntity records only. Email is optional.
 *   true            — creates StudentEntity + AppUserEntity + UserProfileEntity
 *                     in one transaction. Email is required for every row because
 *                     a login account cannot exist without one. Returns temporary
 *                     passwords in the response (shown once, never stored in plaintext).
 *
 * All valid rows commit together. Invalid rows are reported per-row without
 * rolling back the successes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentImportService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[\\w._%+\\-]+@[\\w.\\-]+\\.[a-zA-Z]{2,}$");

    // Password alphabet: excludes 0/O, 1/l/I to prevent misreading
    private static final String PW_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PW_LENGTH = 12;

    private final JpaStudentRepository      studentRepo;
    private final JpaAppUserRepository      userRepo;
    private final JpaSchoolRepository       schoolRepo;
    private final JpaUserProfileRepository  profileRepo;
    private final PasswordEncoder           passwordEncoder;

    private final SecureRandom secureRandom = new SecureRandom();

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Main import method.
     *
     * @param file           the uploaded .xlsx file
     * @param schoolId       the school to import into
     * @param createAccounts when true, also creates login accounts for each student
     */
    @Transactional
    public ImportResult importStudents(MultipartFile file, Long schoolId,
                                       boolean createAccounts) throws IOException {
        ExcelImportUtil.validateFile(file);

        List<Map<String, String>> rows = ExcelImportUtil.parseRows(
                file, "surname", "other names");

        ImportResult.Builder result = ImportResult.builder().totalRows(rows.size());

        // Pre-load existing emails to avoid N round-trips per row
        Set<String> existingStudentEmails = studentRepo.findAllEmailsBySchoolId(schoolId);
        Set<String> seenInFile            = new HashSet<>();

        // Load school entity once — needed by AppUserEntity.school FK when createAccounts=true
        SchoolEntity school = createAccounts
                ? schoolRepo.findById(schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException("School", schoolId))
                : null;

        // Validated rows staged for bulk save
        record ValidRow(String surname, String otherNames, String email) {}
        List<ValidRow> validRows = new ArrayList<>();

        // ── Validation pass ───────────────────────────────────────────────
        for (int i = 0; i < rows.size(); i++) {
            int rowNum = i + 2; // row 1 is header, data starts at row 2
            Map<String, String> row = rows.get(i);

            String surname    = row.getOrDefault("surname",     "").trim();
            String otherNames = row.getOrDefault("other names", "").trim();
            String rawEmail   = row.getOrDefault("email",       "").trim().toLowerCase();
            String email      = rawEmail.isBlank() ? null : rawEmail;

            if (surname.isBlank()) {
                result.error(rowNum, "Surname is required");
                continue;
            }
            if (otherNames.isBlank()) {
                result.error(rowNum, "Other Names is required");
                continue;
            }

            if (createAccounts) {
                // Email is mandatory when creating login accounts
                if (email == null) {
                    result.error(rowNum,
                            "Email is required when createAccounts=true — "
                            + "student accounts cannot be created without a login email");
                    continue;
                }
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
                if (existingStudentEmails.contains(email)) {
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

            validRows.add(new ValidRow(surname, otherNames, email));
        }

        // ── Save pass — all valid rows in one or two saveAll() calls ──────
        List<StudentEntity> students = new ArrayList<>(validRows.size());
        for (ValidRow vr : validRows) {
            students.add(StudentEntity.builder()
                    .surname(vr.surname())
                    .otherNames(vr.otherNames())
                    .email(vr.email())
                    .schoolId(schoolId)
                    .build());
        }
        studentRepo.saveAll(students);
        // After saveAll, each entity has its generated ID assigned by JPA

        if (createAccounts) {
            // Build AppUserEntity + UserProfileEntity for each saved student
            List<AppUserEntity>    users    = new ArrayList<>(students.size());
            List<UserProfileEntity> profiles = new ArrayList<>(students.size());
            List<String>           tempPws  = new ArrayList<>(students.size());

            for (StudentEntity student : students) {
                // email is guaranteed non-null here (validated above)
                String tempPw = generatePassword();
                tempPws.add(tempPw);

                AppUserEntity user = AppUserEntity.builder()
                        .school(school)
                        .email(student.getEmail())
                        .passwordHash(passwordEncoder.encode(tempPw))
                        .role(UserRole.STUDENT)
                        .status(UserStatus.ACTIVE)
                        .build();
                users.add(user);
            }

            userRepo.saveAll(users);
            // Users now have IDs. Build profile links.
            for (int i = 0; i < students.size(); i++) {
                profiles.add(UserProfileEntity.builder()
                        .user(users.get(i))
                        .profileType(ProfileType.STUDENT)
                        .profileId(students.get(i).getId())
                        .build());
                // Record temporary credentials for the response
                result.account(students.get(i).getEmail(), tempPws.get(i));
            }
            profileRepo.saveAll(profiles);
        }

        int saved  = students.size();
        int failed = rows.size() - saved;
        result.successCount(saved).failureCount(failed);

        log.info("Student import: school={} total={} saved={} failed={} createAccounts={}",
                schoolId, rows.size(), saved, failed, createAccounts);
        return result.build();
    }

    /** Returns raw bytes of the .xlsx import template. */
    public byte[] buildTemplate() {
        // Template is the same regardless of createAccounts — no password column needed
        return ExcelImportUtil.buildTemplate(
                new String[]{ "Surname", "Other Names", "Email" },
                new String[]{ "Okonkwo", "Chukwuemeka", "c.okonkwo@school.edu" });
    }

    // ── Password generation ───────────────────────────────────────────────

    /**
     * Generates a secure 12-character temporary password.
     * Uses SecureRandom (cryptographically strong) over the PW_CHARS alphabet
     * which excludes visually ambiguous characters (0/O, 1/l/I).
     *
     * The password is returned in the import response and immediately discarded
     * from memory after hashing — it is never stored in plaintext.
     */
    private String generatePassword() {
        StringBuilder sb = new StringBuilder(PW_LENGTH);
        for (int i = 0; i < PW_LENGTH; i++) {
            sb.append(PW_CHARS.charAt(secureRandom.nextInt(PW_CHARS.length())));
        }
        return sb.toString();
    }
}
