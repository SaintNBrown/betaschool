package com.betaschool.command.handler;

import com.betaschool.command.model.StudentCommand.*;
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

import java.time.OffsetDateTime;

@Slf4j
public class StudentCommandHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateStudentHandler implements CommandHandler<CreateStudentCommand, Long> {

        private final JpaStudentRepository studentRepo;
        private final JpaAppUserRepository userRepo;

        @Override
        public Long handle(CreateStudentCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (cmd.email() != null && studentRepo.existsByEmailAndSchoolId(cmd.email(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "A student with email '" + cmd.email() + "' already exists in this school");
            }

            if (cmd.email() != null && userRepo.existsByEmail(cmd.email())) {
                throw new BusinessRuleViolationException(
                        "Email '" + cmd.email() + "' is already registered as a user login account. "
                        + "Use a different email for the student profile, or leave it blank.");
            }

            StudentEntity student = StudentEntity.builder()
                    .surname(cmd.surname())
                    .otherNames(cmd.otherNames())
                    .email(cmd.email())
                    .schoolId(schoolId)
                    .build();

            StudentEntity saved = studentRepo.save(student);
            log.info("Created student id={} school={}", saved.getId(), schoolId);
            return saved.getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateStudentHandler implements CommandHandler<UpdateStudentCommand, Void> {

        private final JpaStudentRepository studentRepo;

        @Override
        public Void handle(UpdateStudentCommand cmd) {
            Long schoolId = SchoolIdInjector.require();
            StudentEntity student = studentRepo.findByIdAndSchoolId(cmd.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", cmd.studentId()));
            student.setSurname(cmd.surname());
            student.setOtherNames(cmd.otherNames());
            student.setEmail(cmd.email());
            studentRepo.save(student);
            return null;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class EnrollStudentInClassHandler implements CommandHandler<EnrollStudentInClassCommand, Long> {

        private final JpaStudentRepository studentRepo;
        private final JpaClassSessionRepository classSessionRepo;
        private final JpaStudentClassEnrollmentRepository enrollmentRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaStudentSubjectEnrollmentRepository subjectEnrollmentRepo;

        @Override
        public Long handle(EnrollStudentInClassCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (enrollmentRepo.existsByStudentIdAndClassSessionIdAndSchoolId(
                    cmd.studentId(), cmd.classSessionId(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "Student is already enrolled in this class-session");
            }

            ClassSessionEntity classSession = classSessionRepo
                    .findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSession", cmd.classSessionId()));

            // A student can only belong to one class in a specific academic session
            Long sessionId = classSession.getSession().getId();
            if (enrollmentRepo.existsByStudentIdAndSessionIdAndSchoolId(
                    cmd.studentId(), sessionId, schoolId)) {
                throw new BusinessRuleViolationException(
                        "Student is already enrolled in another class for this academic session. "
                        + "A student can only belong to one class per session.");
            }

            StudentEntity student = studentRepo.findByIdAndSchoolId(cmd.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", cmd.studentId()));

            StudentClassEnrollmentEntity enrollment = StudentClassEnrollmentEntity.builder()
                    .student(student)
                    .classSession(classSession)
                    .enrolledAt(OffsetDateTime.now())
                    .schoolId(schoolId)
                    .build();

            StudentClassEnrollmentEntity saved = enrollmentRepo.save(enrollment);

            // Auto-enroll in all compulsory subjects
            classSubjectRepo.findCompulsoryByClassSessionIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .forEach(cs -> {
                        if (!subjectEnrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                                cmd.studentId(), cs.getId(), schoolId)) {
                            subjectEnrollmentRepo.save(
                                    StudentSubjectEnrollmentEntity.builder()
                                            .student(student)
                                            .classSubject(cs)
                                            .schoolId(schoolId)
                                            .build());
                        }
                    });

            log.info("Enrolled student={} in classSession={} school={}", cmd.studentId(), cmd.classSessionId(), schoolId);
            return saved.getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class EnrollStudentInSubjectHandler implements CommandHandler<EnrollStudentInSubjectCommand, Long> {

        private final JpaStudentRepository studentRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaStudentSubjectEnrollmentRepository subjectEnrollmentRepo;
        private final JpaStudentClassEnrollmentRepository classEnrollmentRepo;

        @Override
        public Long handle(EnrollStudentInSubjectCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            StudentEntity student = studentRepo.findByIdAndSchoolId(cmd.studentId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student", cmd.studentId()));

            ClassSubjectEntity classSubject = classSubjectRepo
                    .findByIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", cmd.classSubjectId()));

            // Compulsory subjects are auto-enrolled at class enrollment time.
            // Manual enrollment via this endpoint is only permitted for elective subjects.
            if (!classSubject.isElective()) {
                throw new BusinessRuleViolationException(
                        "'" + classSubject.getSubject().getName() + "' is a compulsory subject. "
                        + "Students are automatically enrolled in compulsory subjects when they join the class.");
            }

            if (!classEnrollmentRepo.existsByStudentIdAndClassSessionIdAndSchoolId(
                    cmd.studentId(), classSubject.getClassSession().getId(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "Student must be enrolled in the class before enrolling in an elective subject");
            }

            if (subjectEnrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                    cmd.studentId(), cmd.classSubjectId(), schoolId)) {
                throw new BusinessRuleViolationException("Student is already enrolled in this subject");
            }

            StudentSubjectEnrollmentEntity enrollment = StudentSubjectEnrollmentEntity.builder()
                    .student(student)
                    .classSubject(classSubject)
                    .schoolId(schoolId)
                    .build();

            return subjectEnrollmentRepo.save(enrollment).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UnenrollStudentFromSubjectHandler
            implements CommandHandler<UnenrollStudentFromSubjectCommand, Void> {

        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaStudentSubjectEnrollmentRepository subjectEnrollmentRepo;

        @Override
        public Void handle(UnenrollStudentFromSubjectCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ClassSubjectEntity classSubject = classSubjectRepo
                    .findByIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", cmd.classSubjectId()));

            if (!classSubject.isElective()) {
                throw new BusinessRuleViolationException("Cannot unenroll from a compulsory subject");
            }

            StudentSubjectEnrollmentEntity enrollment = subjectEnrollmentRepo
                    .findByStudentIdAndClassSubjectIdAndSchoolId(cmd.studentId(), cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Student is not enrolled in this subject"));

            subjectEnrollmentRepo.delete(enrollment);
            return null;
        }
    }
}
