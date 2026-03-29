package com.betaschool.command.handler;

import com.betaschool.command.model.TeacherCommand.*;
import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.infrastructure.persistence.repository.auth.JpaAppUserRepository;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.SchoolIdInjector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
public class TeacherCommandHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateTeacherHandler implements CommandHandler<CreateTeacherCommand, Long> {

        private final JpaTeacherRepository teacherRepo;
        private final JpaAppUserRepository userRepo;

        @Override
        public Long handle(CreateTeacherCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (teacherRepo.existsByEmailAndSchoolId(cmd.email(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "A teacher with email '" + cmd.email() + "' already exists in this school");
            }

            // Prevent using an existing app_user login email as a teacher profile email.
            // The teacher profile email and the login account email are different fields
            // in different tables — this check prevents silent confusion where a school
            // admin creates a teacher profile with their own login email.
            if (cmd.email() != null && userRepo.existsByEmail(cmd.email())) {
                throw new BusinessRuleViolationException(
                        "Email '" + cmd.email() + "' is already registered as a user login account. "
                        + "Use a different email for the teacher profile, or leave it blank.");
            }

            TeacherEntity teacher = TeacherEntity.builder()
                    .surname(cmd.surname())
                    .otherNames(cmd.otherNames())
                    .email(cmd.email())
                    .schoolId(schoolId)
                    .build();

            TeacherEntity saved = teacherRepo.save(teacher);
            log.info("Created teacher id={} school={}", saved.getId(), schoolId);
            return saved.getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateTeacherHandler implements CommandHandler<UpdateTeacherCommand, Void> {

        private final JpaTeacherRepository teacherRepo;

        @Override
        public Void handle(UpdateTeacherCommand cmd) {
            Long schoolId = SchoolIdInjector.require();
            TeacherEntity teacher = teacherRepo.findByIdAndSchoolId(cmd.teacherId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", cmd.teacherId()));

            teacher.setSurname(cmd.surname());
            teacher.setOtherNames(cmd.otherNames());
            teacher.setEmail(cmd.email());
            teacherRepo.save(teacher);
            return null;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class AssignTeacherToClassHandler
            implements CommandHandler<AssignTeacherToClassCommand, Long> {

        private final JpaTeacherRepository teacherRepo;
        private final JpaClassSessionRepository classSessionRepo;
        private final JpaTeacherClassAssignmentRepository assignmentRepo;

        @Override
        public Long handle(AssignTeacherToClassCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            // ── Idempotency: if already assigned, don't error — just update formTeacher if needed ──
            var existingOpt = assignmentRepo.findByTeacherIdAndClassSessionIdAndSchoolId(
                    cmd.teacherId(), cmd.classSessionId(), schoolId);
            if (existingOpt.isPresent()) {
                TeacherClassAssignmentEntity existing = existingOpt.get();
                if (cmd.isFormTeacher() && !existing.isFormTeacher()) {
                    // Check no other teacher already holds form teacher for this class
                    assignmentRepo.findByClassSessionIdAndFormTeacherTrueAndSchoolId(cmd.classSessionId(), schoolId)
                            .ifPresent(other -> {
                                if (!other.getTeacher().getId().equals(cmd.teacherId())) {
                                    throw new BusinessRuleViolationException(
                                            "Class-session already has a different form teacher assigned");
                                }
                            });
                    existing.setFormTeacher(true);
                    assignmentRepo.save(existing);
                }
                return existing.getId();
            }

            TeacherEntity teacher = teacherRepo.findByIdAndSchoolId(cmd.teacherId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", cmd.teacherId()));

            ClassSessionEntity classSession = classSessionRepo
                    .findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSession", cmd.classSessionId()));

            // For a brand-new assignment with isFormTeacher=true, guard against
            // displacing an existing form teacher from a different teacher.
            if (cmd.isFormTeacher()) {
                assignmentRepo.findByClassSessionIdAndFormTeacherTrueAndSchoolId(cmd.classSessionId(), schoolId)
                        .ifPresent(other -> {
                            if (!other.getTeacher().getId().equals(cmd.teacherId())) {
                                throw new BusinessRuleViolationException(
                                        "Class-session already has a different form teacher assigned. " +
                                        "Unset the existing form teacher first.");
                            }
                        });
            }

            TeacherClassAssignmentEntity assignment = TeacherClassAssignmentEntity.builder()
                    .teacher(teacher)
                    .classSession(classSession)
                    .formTeacher(cmd.isFormTeacher())
                    .schoolId(schoolId)
                    .build();

            return assignmentRepo.save(assignment).getId();
        }
    }

    /**
     * Assigns a teacher to a class-subject for the first time.
     * A teacher may be assigned to multiple class-subjects (teaching multiple
     * subjects across different classes is fully supported). The only constraint
     * is one teacher per class-subject — not one subject per teacher.
     *
     * To replace an existing assignment, use ReassignTeacherToSubjectHandler.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class AssignTeacherToSubjectHandler
            implements CommandHandler<AssignTeacherToSubjectCommand, Long> {

        private final JpaTeacherRepository teacherRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaTeacherSubjectAssignmentRepository assignmentRepo;

        @Override
        public Long handle(AssignTeacherToSubjectCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            TeacherEntity teacher = teacherRepo.findByIdAndSchoolId(cmd.teacherId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", cmd.teacherId()));

            ClassSubjectEntity classSubject = classSubjectRepo
                    .findByIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", cmd.classSubjectId()));

            // Check if an assignment already exists for this class-subject
            var existingOpt = assignmentRepo.findByClassSubjectIdAndSchoolId(cmd.classSubjectId(), schoolId);
            if (existingOpt.isPresent()) {
                TeacherSubjectAssignmentEntity existing = existingOpt.get();
                if (existing.getTeacher().getId().equals(cmd.teacherId())) {
                    // Idempotent: same teacher already assigned — return existing ID
                    log.info("Teacher id={} is already assigned to classSubject id={} — returning existing",
                            cmd.teacherId(), cmd.classSubjectId());
                    return existing.getId();
                }
                // Different teacher already assigned — require explicit reassign
                throw new BusinessRuleViolationException(
                        "This class-subject already has a different teacher assigned. "
                        + "Use PUT /teachers/{teacherId}/assign/subject/{classSubjectId} to reassign.");
            }

            TeacherSubjectAssignmentEntity assignment = TeacherSubjectAssignmentEntity.builder()
                    .teacher(teacher)
                    .classSubject(classSubject)
                    .schoolId(schoolId)
                    .build();

            return assignmentRepo.save(assignment).getId();
        }
    }

    /**
     * Removes a teacher from a class-subject entirely.
     * The class-subject remains in the curriculum — it just has no assigned teacher.
     * The optional teacherId parameter is validated if provided.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UnassignTeacherFromSubjectHandler
            implements CommandHandler<UnassignTeacherFromSubjectCommand, Void> {

        private final JpaTeacherSubjectAssignmentRepository assignmentRepo;

        @Override
        public Void handle(UnassignTeacherFromSubjectCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            TeacherSubjectAssignmentEntity assignment = assignmentRepo
                    .findByClassSubjectIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No teacher is currently assigned to class-subject id=" + cmd.classSubjectId()));

            // If a specific teacherId is provided, verify it matches the current assignment
            if (cmd.teacherId() != null
                    && !assignment.getTeacher().getId().equals(cmd.teacherId())) {
                throw new BusinessRuleViolationException(
                        "Teacher id=" + cmd.teacherId()
                        + " is not the currently assigned teacher for this subject");
            }

            assignmentRepo.delete(assignment);
            log.info("Unassigned teacher={} from classSubject={} school={}",
                    assignment.getTeacher().getId(), cmd.classSubjectId(), schoolId);
            return null;
        }
    }

    /**
     * Atomically replaces the existing teacher on a class-subject with a new teacher.
     * This is the correct endpoint to use when reassigning a subject mid-session.
     * Performed in one transaction so there is no window where the subject is unassigned.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class ReassignTeacherToSubjectHandler
            implements CommandHandler<ReassignTeacherToSubjectCommand, Void> {

        private final JpaTeacherRepository teacherRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaTeacherSubjectAssignmentRepository assignmentRepo;

        @Override
        public Void handle(ReassignTeacherToSubjectCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            TeacherEntity newTeacher = teacherRepo.findByIdAndSchoolId(cmd.newTeacherId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", cmd.newTeacherId()));

            classSubjectRepo.findByIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", cmd.classSubjectId()));

            TeacherSubjectAssignmentEntity assignment = assignmentRepo
                    .findByClassSubjectIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No teacher is currently assigned to class-subject id=" + cmd.classSubjectId()
                            + ". Use POST /teachers/{teacherId}/assign/subject/{classSubjectId} to assign first."));

            Long previousTeacherId = assignment.getTeacher().getId();
            assignment.setTeacher(newTeacher);
            assignmentRepo.save(assignment);

            log.info("Reassigned classSubject={} from teacher={} to teacher={} school={}",
                    cmd.classSubjectId(), previousTeacherId, cmd.newTeacherId(), schoolId);
            return null;
        }
    }

    /**
     * Assigns a teacher to multiple specific subjects in one transaction.
     * Secondary-school model: a teacher teaches a chosen set of subjects
     * (e.g. Maths + Basic Science + Basic Tech) rather than all subjects.
     *
     * Subjects with no teacher → assigned.
     * Subjects already owned by THIS teacher → skipped (idempotent).
     * Subjects owned by a DIFFERENT teacher → skipped; names returned in result
     *   so the admin can review and use the explicit reassign endpoint if needed.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class BulkAssignTeacherToSubjectsHandler
            implements CommandHandler<BulkAssignTeacherToSubjectsCommand, BulkAssignmentResult> {

        private final JpaTeacherRepository teacherRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaTeacherSubjectAssignmentRepository assignmentRepo;

        @Override
        public BulkAssignmentResult handle(BulkAssignTeacherToSubjectsCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (cmd.classSubjectIds() == null || cmd.classSubjectIds().isEmpty()) {
                throw new BusinessRuleViolationException(
                        "classSubjectIds must not be empty. Provide at least one subject to assign.");
            }

            TeacherEntity teacher = teacherRepo.findByIdAndSchoolId(cmd.teacherId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", cmd.teacherId()));

            int assigned            = 0;
            int alreadyOwned        = 0;
            int skippedOtherTeacher = 0;
            List<String> skippedNames  = new java.util.ArrayList<>();
            List<TeacherSubjectAssignmentEntity> toSave = new java.util.ArrayList<>();

            for (Long classSubjectId : cmd.classSubjectIds()) {
                ClassSubjectEntity subject = classSubjectRepo
                        .findByIdAndSchoolId(classSubjectId, schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", classSubjectId));

                var existingOpt = assignmentRepo.findByClassSubjectIdAndSchoolId(classSubjectId, schoolId);

                if (existingOpt.isPresent()) {
                    TeacherSubjectAssignmentEntity existing = existingOpt.get();
                    if (existing.getTeacher().getId().equals(cmd.teacherId())) {
                        alreadyOwned++;
                    } else {
                        // Different teacher already owns this subject — skip.
                        // Admin must use PUT /teachers/{id}/assign/subject/{id} to explicitly reassign.
                        skippedOtherTeacher++;
                        skippedNames.add(subject.getSubject().getName());
                    }
                } else {
                    toSave.add(TeacherSubjectAssignmentEntity.builder()
                            .teacher(teacher)
                            .classSubject(subject)
                            .schoolId(schoolId)
                            .build());
                    assigned++;
                }
            }

            if (!toSave.isEmpty()) {
                assignmentRepo.saveAll(toSave);
            }

            log.info("Bulk assign: teacher={} assigned={} alreadyOwned={} skipped={} school={}",
                    cmd.teacherId(), assigned, alreadyOwned, skippedOtherTeacher, schoolId);

            return new BulkAssignmentResult(assigned, alreadyOwned, skippedOtherTeacher, skippedNames);
        }
    }

    /**
     * Primary-school form teacher bulk assignment.
     *
     * Assigns the given teacher to every subject in the class-session in a single
     * transaction.  Subjects that already have a DIFFERENT teacher are skipped so
     * specialist teachers (e.g. a dedicated music teacher) are never displaced.
     * Subjects already owned by THIS teacher are left untouched (idempotent).
     *
     * Optionally also sets the teacher as the class-session's form teacher.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class AssignFormTeacherToAllSubjectsHandler
            implements CommandHandler<AssignFormTeacherToAllSubjectsCommand, FormTeacherAssignmentResult> {

        private final JpaTeacherRepository teacherRepo;
        private final JpaClassSessionRepository classSessionRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaTeacherSubjectAssignmentRepository subjectAssignmentRepo;
        private final JpaTeacherClassAssignmentRepository classAssignmentRepo;

        @Override
        public FormTeacherAssignmentResult handle(AssignFormTeacherToAllSubjectsCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            TeacherEntity teacher = teacherRepo.findByIdAndSchoolId(cmd.teacherId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", cmd.teacherId()));

            ClassSessionEntity classSession = classSessionRepo
                    .findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSession", cmd.classSessionId()));

            // ── Optionally set / update the form teacher on the class ─────────
            if (cmd.setAsFormTeacher()) {
                var existingFormTeacher = classAssignmentRepo
                        .findByClassSessionIdAndFormTeacherTrueAndSchoolId(cmd.classSessionId(), schoolId);

                if (existingFormTeacher.isPresent()) {
                    // Update in-place — change the form teacher to this one
                    TeacherClassAssignmentEntity existing = existingFormTeacher.get();
                    if (!existing.getTeacher().getId().equals(cmd.teacherId())) {
                        existing.setFormTeacher(false);
                        classAssignmentRepo.save(existing);
                    }
                }

                // Ensure this teacher has a class assignment (create if missing)
                if (!classAssignmentRepo.existsByTeacherIdAndClassSessionIdAndSchoolId(
                        cmd.teacherId(), cmd.classSessionId(), schoolId)) {
                    classAssignmentRepo.save(TeacherClassAssignmentEntity.builder()
                            .teacher(teacher)
                            .classSession(classSession)
                            .formTeacher(true)
                            .schoolId(schoolId)
                            .build());
                } else {
                    // Already assigned to the class — just make sure formTeacher flag is set
                    classAssignmentRepo
                            .findByTeacherIdAndClassSessionIdAndSchoolId(
                                    cmd.teacherId(), cmd.classSessionId(), schoolId)
                            .ifPresent(a -> {
                                if (!a.isFormTeacher()) {
                                    a.setFormTeacher(true);
                                    classAssignmentRepo.save(a);
                                }
                            });
                }
            }

            // ── Assign teacher to all subjects ────────────────────────────────
            List<ClassSubjectEntity> allSubjects = classSubjectRepo
                    .findByClassSessionIdAndSchoolId(cmd.classSessionId(), schoolId);

            if (allSubjects.isEmpty()) {
                throw new BusinessRuleViolationException(
                        "This class-session has no subjects yet. Add subjects first before assigning a form teacher.");
            }

            int assigned           = 0;
            int alreadyOwned       = 0;
            int skippedOtherTeacher = 0;
            List<String> skippedNames = new java.util.ArrayList<>();

            List<TeacherSubjectAssignmentEntity> toSave = new java.util.ArrayList<>();

            for (ClassSubjectEntity subject : allSubjects) {
                var existingOpt = subjectAssignmentRepo
                        .findByClassSubjectIdAndSchoolId(subject.getId(), schoolId);

                if (existingOpt.isPresent()) {
                    TeacherSubjectAssignmentEntity existing = existingOpt.get();
                    if (existing.getTeacher().getId().equals(cmd.teacherId())) {
                        // This teacher already owns it — no change needed
                        alreadyOwned++;
                    } else {
                        // Different teacher — skip to preserve specialist assignment
                        skippedOtherTeacher++;
                        skippedNames.add(subject.getSubject().getName());
                    }
                } else {
                    // Unassigned — assign to this teacher
                    toSave.add(TeacherSubjectAssignmentEntity.builder()
                            .teacher(teacher)
                            .classSubject(subject)
                            .schoolId(schoolId)
                            .build());
                    assigned++;
                }
            }

            if (!toSave.isEmpty()) {
                subjectAssignmentRepo.saveAll(toSave);
            }

            log.info("Form teacher assignment: teacher={} classSession={} assigned={} alreadyOwned={} skipped={}",
                    cmd.teacherId(), cmd.classSessionId(), assigned, alreadyOwned, skippedOtherTeacher);

            return new FormTeacherAssignmentResult(
                    assigned, alreadyOwned, skippedOtherTeacher, skippedNames);
        }
    }
}
