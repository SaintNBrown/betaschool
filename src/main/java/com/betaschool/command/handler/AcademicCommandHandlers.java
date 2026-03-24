package com.betaschool.command.handler;

import com.betaschool.command.model.AcademicCommand.*;
import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.SchoolIdInjector;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
public class AcademicCommandHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateSessionHandler implements CommandHandler<CreateSessionCommand, Long> {

        private final JpaSessionRepository sessionRepo;

        @Override
        public Long handle(CreateSessionCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (sessionRepo.existsBySessionNameAndSchoolId(cmd.sessionName(), schoolId)) {
                throw new BusinessRuleViolationException("Session '" + cmd.sessionName() + "' already exists");
            }
            if (!cmd.closingDate().isAfter(cmd.startDate())) {
                throw new BusinessRuleViolationException("Closing date must be after start date");
            }

            SessionEntity session = SessionEntity.builder()
                    .sessionName(cmd.sessionName())
                    .startDate(cmd.startDate())
                    .closingDate(cmd.closingDate())
                    .schoolId(schoolId)
                    .build();

            return sessionRepo.save(session).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateSessionHandler implements CommandHandler<UpdateSessionCommand, Void> {

        private final JpaSessionRepository sessionRepo;

        @Override
        public Void handle(UpdateSessionCommand cmd) {
            Long schoolId = SchoolIdInjector.require();
            SessionEntity session = sessionRepo.findByIdAndSchoolId(cmd.sessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", cmd.sessionId()));

            session.setSessionName(cmd.sessionName());
            session.setStartDate(cmd.startDate());
            session.setClosingDate(cmd.closingDate());
            sessionRepo.save(session);
            return null;
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateClassHandler implements CommandHandler<CreateClassCommand, Long> {

        private final JpaClassRepository classRepo;

        @Override
        public Long handle(CreateClassCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (classRepo.existsByNameAndSchoolId(cmd.name(), schoolId)) {
                throw new BusinessRuleViolationException("Class '" + cmd.name() + "' already exists in this school");
            }
            return classRepo.save(ClassEntity.builder()
                    .name(cmd.name()).schoolId(schoolId).build()).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class OpenClassInSessionHandler implements CommandHandler<OpenClassInSessionCommand, Long> {

        private final JpaClassRepository classRepo;
        private final JpaSessionRepository sessionRepo;
        private final JpaClassSessionRepository classSessionRepo;

        @Override
        public Long handle(OpenClassInSessionCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (classSessionRepo.existsByClazzIdAndSessionIdAndSchoolId(
                    cmd.classId(), cmd.sessionId(), schoolId)) {
                throw new BusinessRuleViolationException("This class is already open in the given session");
            }

            ClassEntity clazz = classRepo.findByIdAndSchoolId(cmd.classId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Class", cmd.classId()));

            SessionEntity session = sessionRepo.findByIdAndSchoolId(cmd.sessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Session", cmd.sessionId()));

            ClassSessionEntity classSession = ClassSessionEntity.builder()
                    .clazz(clazz)
                    .session(session)
                    .schoolId(schoolId)
                    .build();

            return classSessionRepo.save(classSession).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateSubjectHandler implements CommandHandler<CreateSubjectCommand, Long> {

        private final JpaSubjectRepository subjectRepo;

        @Override
        public Long handle(CreateSubjectCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (subjectRepo.existsByNameAndSchoolId(cmd.name(), schoolId)) {
                throw new BusinessRuleViolationException("Subject '" + cmd.name() + "' already exists in this school");
            }
            return subjectRepo.save(SubjectEntity.builder()
                    .name(cmd.name()).schoolId(schoolId).build()).getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class AddSubjectToClassHandler implements CommandHandler<AddSubjectToClassCommand, Long> {

        private final JpaClassSessionRepository classSessionRepo;
        private final JpaSubjectRepository subjectRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaStudentClassEnrollmentRepository studentClassEnrollmentRepo;
        private final JpaStudentSubjectEnrollmentRepository studentSubjectEnrollmentRepo;

        @Override
        public Long handle(AddSubjectToClassCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (classSubjectRepo.existsByClassSessionIdAndSubjectIdAndSchoolId(
                    cmd.classSessionId(), cmd.subjectId(), schoolId)) {
                throw new BusinessRuleViolationException("Subject is already added to this class-session");
            }

            ClassSessionEntity classSession = classSessionRepo
                    .findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSession", cmd.classSessionId()));

            SubjectEntity subject = subjectRepo.findByIdAndSchoolId(cmd.subjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Subject", cmd.subjectId()));

            ClassSubjectEntity cs = ClassSubjectEntity.builder()
                    .classSession(classSession)
                    .subject(subject)
                    .elective(cmd.isElective())
                    .schoolId(schoolId)
                    .build();

            cs = classSubjectRepo.save(cs);

            // Issue 6: when a NEW COMPULSORY subject is added to a class-session that already
            // has enrolled students, retroactively enroll every existing student in the subject.
            // Without this, previously enrolled students miss the subject from their report card.
            if (!cmd.isElective()) {
                final ClassSubjectEntity savedCs = cs;
                studentClassEnrollmentRepo
                        .findByClassSessionIdAndSchoolId(cmd.classSessionId(), schoolId)
                        .forEach(enrollment -> {
                            Long studentId = enrollment.getStudent().getId();
                            if (!studentSubjectEnrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                                    studentId, savedCs.getId(), schoolId)) {
                                studentSubjectEnrollmentRepo.save(
                                        StudentSubjectEnrollmentEntity.builder()
                                                .student(enrollment.getStudent())
                                                .classSubject(savedCs)
                                                .schoolId(schoolId)
                                                .build());
                            }
                        });
            }

            return cs.getId();
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class CreateTermHandler implements CommandHandler<CreateTermCommand, Long> {

        private final JpaClassSessionRepository classSessionRepo;
        private final JpaTermRepository termRepo;

        @Override
        public Long handle(CreateTermCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            if (cmd.termNumber() < 1 || cmd.termNumber() > 3) {
                throw new BusinessRuleViolationException("Term number must be 1, 2, or 3");
            }

            if (termRepo.existsByClassSessionIdAndTermNumberAndSchoolId(
                    cmd.classSessionId(), cmd.termNumber(), schoolId)) {
                throw new BusinessRuleViolationException(
                        "Term " + cmd.termNumber() + " already exists for this class-session");
            }

            ClassSessionEntity classSession = classSessionRepo
                    .findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSession", cmd.classSessionId()));

            TermEntity term = TermEntity.builder()
                    .classSession(classSession)
                    .session(classSession.getSession())
                    .termNumber(cmd.termNumber())
                    .schoolId(schoolId)
                    .build();

            return termRepo.save(term).getId();
        }
    }

    /**
     * Toggles a class-subject between compulsory and elective.
     * compulsory → elective: existing student enrollments are kept.
     * elective → compulsory: all enrolled students in the class-session who
     *   are not yet enrolled in this subject are auto-enrolled immediately.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class UpdateClassSubjectTypeHandler
            implements CommandHandler<UpdateClassSubjectTypeCommand, Void> {

        private final JpaClassSubjectRepository classSubjectRepo;
        private final JpaStudentClassEnrollmentRepository studentClassEnrollmentRepo;
        private final JpaStudentSubjectEnrollmentRepository studentSubjectEnrollmentRepo;

        @Override
        public Void handle(UpdateClassSubjectTypeCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            ClassSubjectEntity cs = classSubjectRepo.findByIdAndSchoolId(cmd.classSubjectId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("ClassSubject", cmd.classSubjectId()));

            if (cs.isElective() == cmd.isElective()) {
                // No change needed
                return null;
            }

            cs.setElective(cmd.isElective());
            classSubjectRepo.save(cs);

            // If switching to compulsory, auto-enroll all currently enrolled class students
            if (!cmd.isElective()) {
                final ClassSubjectEntity savedCs = cs;
                studentClassEnrollmentRepo
                        .findByClassSessionIdAndSchoolId(cs.getClassSession().getId(), schoolId)
                        .forEach(enrollment -> {
                            Long studentId = enrollment.getStudent().getId();
                            if (!studentSubjectEnrollmentRepo.existsByStudentIdAndClassSubjectIdAndSchoolId(
                                    studentId, savedCs.getId(), schoolId)) {
                                studentSubjectEnrollmentRepo.save(
                                        StudentSubjectEnrollmentEntity.builder()
                                                .student(enrollment.getStudent())
                                                .classSubject(savedCs)
                                                .schoolId(schoolId)
                                                .build());
                            }
                        });
                log.info("Subject '{}' changed to compulsory for classSession={} — auto-enrolled existing students",
                        cs.getSubject().getName(), cs.getClassSession().getId());
            } else {
                log.info("Subject '{}' changed to elective for classSession={}",
                        cs.getSubject().getName(), cs.getClassSession().getId());
            }
            return null;
        }
    }
     /* Clears any existing current flag within the school before setting the new one.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class SetCurrentSessionHandler implements CommandHandler<SetCurrentSessionCommand, Void> {

        private final JpaSessionRepository sessionRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Void handle(SetCurrentSessionCommand cmd) {
            tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");

            // SYSTEM_ADMIN has no school in context; resolve school from the session itself.
            // SCHOOL_ADMIN uses their own school from context and the session must belong to it.
            SessionEntity session;
            if (TenantContext.isSystemAdmin()) {
                session = sessionRepo.findById(cmd.sessionId())
                        .orElseThrow(() -> new ResourceNotFoundException("Session", cmd.sessionId()));
            } else {
                Long schoolId = SchoolIdInjector.require();
                session = sessionRepo.findByIdAndSchoolId(cmd.sessionId(), schoolId)
                        .orElseThrow(() -> new ResourceNotFoundException("Session", cmd.sessionId()));
            }

            Long schoolId = session.getSchoolId();

            // Clear any existing current flag for this school first
            sessionRepo.clearCurrentSessionForSchool(schoolId);

            session.setCurrent(true);
            sessionRepo.save(session);

            log.info("Session id={} name='{}' set as current for school={}",
                    session.getId(), session.getSessionName(), schoolId);
            return null;
        }
    }
}
