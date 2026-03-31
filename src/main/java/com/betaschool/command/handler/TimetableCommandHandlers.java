package com.betaschool.command.handler;

import com.betaschool.command.model.TimetableCommand.DeleteTimetableVersionCommand;
import com.betaschool.command.model.TimetableCommand.PublishTimetableCommand;
import com.betaschool.command.model.TimetableCommand.TimetableSlotInput;
import com.betaschool.infrastructure.persistence.entity.*;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.DayOfWeek;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.SlotType;
import com.betaschool.infrastructure.persistence.repository.JpaClassSessionRepository;
import com.betaschool.infrastructure.persistence.repository.JpaClassSubjectRepository;
import com.betaschool.infrastructure.persistence.repository.JpaTimetableRepository;
import com.betaschool.infrastructure.persistence.repository.JpaTimetableSlotRepository;
import com.betaschool.query.model.TimetableQueryResult.TeacherConflict;
import com.betaschool.shared.CommandHandler;
import com.betaschool.shared.exception.BusinessRuleViolationException;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.SchoolIdInjector;
import com.betaschool.tenant.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class TimetableCommandHandlers {

    private static final int MAX_HISTORY_VERSIONS = 5;

    /**
     * Publishes a new timetable version for a class-session.
     *
     * Steps performed atomically:
     *  1. Validate all slots (type constraints, time ordering, overlap detection,
     *     class-subject ownership for SUBJECT slots)
     *  2. Deactivate the current active timetable (if any)
     *  3. Create the new timetable entity at next version number
     *  4. Save all slots linked to the new timetable
     *  5. Prune oldest historical versions if total exceeds MAX_HISTORY_VERSIONS
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class PublishTimetableHandler
            implements CommandHandler<PublishTimetableCommand, Long> {

        private final JpaTimetableRepository timetableRepo;
        private final JpaTimetableSlotRepository slotRepo;
        private final JpaClassSessionRepository classSessionRepo;
        private final JpaClassSubjectRepository classSubjectRepo;
        public Long handle(PublishTimetableCommand cmd) {
            Long schoolId = SchoolIdInjector.require();
            Long currentUserId = TenantContext.getUserId();

            ClassSessionEntity classSession = classSessionRepo
                    .findByIdAndSchoolId(cmd.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "ClassSession", cmd.classSessionId()));

            if (cmd.slots() == null || cmd.slots().isEmpty()) {
                throw new BusinessRuleViolationException(
                        "A timetable must contain at least one slot");
            }

            // ── Validate every slot ───────────────────────────────────────────
            List<TimetableSlotEntity> validatedSlots = new ArrayList<>();

            for (TimetableSlotInput input : cmd.slots()) {
                // Parse and validate enums
                DayOfWeek day;
                try {
                    day = DayOfWeek.valueOf(input.dayOfWeek().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new BusinessRuleViolationException(
                            "Invalid day of week: '" + input.dayOfWeek()
                            + "'. Must be one of: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY");
                }

                SlotType slotType;
                try {
                    slotType = SlotType.valueOf(input.slotType().toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new BusinessRuleViolationException(
                            "Invalid slot type: '" + input.slotType()
                            + "'. Must be SUBJECT or ACTIVITY");
                }

                // Time ordering
                if (!input.endTime().isAfter(input.startTime())) {
                    throw new BusinessRuleViolationException(
                            "Slot end time must be after start time (got "
                            + input.startTime() + " - " + input.endTime() + ")");
                }

                ClassSubjectEntity classSubject = null;
                String activityLabel = null;

                if (slotType == SlotType.SUBJECT) {
                    if (input.classSubjectId() == null) {
                        throw new BusinessRuleViolationException(
                                "SUBJECT slots must include a classSubjectId");
                    }
                    classSubject = classSubjectRepo
                            .findByIdAndSchoolId(input.classSubjectId(), schoolId)
                            .orElseThrow(() -> new ResourceNotFoundException(
                                    "ClassSubject", input.classSubjectId()));
                    // Verify the subject belongs to this class-session
                    if (!classSubject.getClassSession().getId().equals(cmd.classSessionId())) {
                        throw new BusinessRuleViolationException(
                                "ClassSubject id=" + input.classSubjectId()
                                + " does not belong to class-session id=" + cmd.classSessionId());
                    }
                } else {
                    // ACTIVITY
                    if (input.activityLabel() == null || input.activityLabel().isBlank()) {
                        throw new BusinessRuleViolationException(
                                "ACTIVITY slots must include a non-blank activityLabel");
                    }
                    activityLabel = input.activityLabel().trim();
                }

                validatedSlots.add(TimetableSlotEntity.builder()
                        .schoolId(schoolId)
                        .dayOfWeek(day)
                        .startTime(input.startTime())
                        .endTime(input.endTime())
                        .slotType(slotType)
                        .classSubject(classSubject)
                        .activityLabel(activityLabel)
                        .sortOrder(input.sortOrder() != null ? input.sortOrder() : 0)
                        .build());
            }

            // ── Overlap detection per day (within this class) ────────────────
            validateNoOverlaps(validatedSlots);

            // ── Cross-class teacher conflict detection ───────────────────────
            // Collects ALL conflicts before throwing so the admin sees everything
            // in one response instead of fixing one at a time.
            List<TeacherConflict> teacherConflicts =
                    detectTeacherConflicts(validatedSlots, cmd.classSessionId(), schoolId);
            if (!teacherConflicts.isEmpty()) {
                String detail = teacherConflicts.stream()
                        .map(c -> String.format(
                                "Teacher conflict: %s is already scheduled in %s at %s %s–%s " +
                                "(subject: %s conflicts with %s)",
                                c.teacherName(), c.conflictingClassName(), c.dayOfWeek(),
                                c.conflictingStart(), c.conflictingEnd(),
                                c.subjectBeingPublished(), c.subjectAlreadyScheduled()))
                        .collect(Collectors.joining("\n"));
                throw new BusinessRuleViolationException(
                        "Timetable has " + teacherConflicts.size()
                        + " teacher conflict(s):\n" + detail);
            }

            // ── Deactivate current active timetable ──────────────────────────
            timetableRepo.deactivateAllForClassSession(cmd.classSessionId(), schoolId);

            // ── Determine next version number ────────────────────────────────
            int nextVersion = timetableRepo.findMaxVersionByClassSessionIdAndSchoolId(
                    cmd.classSessionId(), schoolId) + 1;

            // ── Save the new timetable ───────────────────────────────────────
            TimetableEntity timetable = TimetableEntity.builder()
                    .schoolId(schoolId)
                    .classSession(classSession)
                    .version(nextVersion)
                    .active(true)
                    .notes(cmd.notes())
                    .createdBy(currentUserId)
                    .build();
            timetable = timetableRepo.save(timetable);

            // Link slots to the saved timetable and persist
            final TimetableEntity savedTimetable = timetable;
            validatedSlots.forEach(slot -> slot.setTimetable(savedTimetable));
            slotRepo.saveAll(validatedSlots);

            // ── Prune oldest versions beyond the cap ─────────────────────────
            pruneOldVersions(cmd.classSessionId(), schoolId);

            log.info("Published timetable v{} for classSession={} school={}",
                    nextVersion, cmd.classSessionId(), schoolId);

            return savedTimetable.getId();
        }

        /**
         * Checks every SUBJECT slot in the timetable being published against all
         * currently active timetables in the same school.
         *
         * A conflict exists when the same teacher is scheduled in a different class
         * at an overlapping time on the same day. The teacher is resolved via the
         * class-subject's current teacher_subject_assignment — if no teacher is
         * assigned to the subject yet, the slot is skipped (no conflict possible).
         *
         * Returns all conflicts found (never throws) so the caller can collect and
         * report them all at once.
         */
        List<TeacherConflict> detectTeacherConflicts(
                List<TimetableSlotEntity> slots, Long classSessionId, Long schoolId) {

            List<TeacherConflict> conflicts = new ArrayList<>();

            for (TimetableSlotEntity slot : slots) {
                if (slot.getSlotType() != SlotType.SUBJECT) continue;
                if (slot.getClassSubject() == null) continue;
                if (slot.getClassSubject().getTeacherAssignment() == null) continue;

                TeacherEntity teacher = slot.getClassSubject().getTeacherAssignment().getTeacher();
                String subjectBeingPublished = slot.getClassSubject().getSubject().getName();

                List<TimetableSlotEntity> conflicting = slotRepo.findConflictingTeacherSlots(
                        teacher.getId(),
                        slot.getDayOfWeek(),
                        slot.getStartTime(),
                        slot.getEndTime(),
                        classSessionId,
                        schoolId);

                for (TimetableSlotEntity conflict : conflicting) {
                    String conflictingClassName = conflict.getTimetable()
                            .getClassSession().getClazz().getName();
                    String alreadyScheduledSubject = (conflict.getClassSubject() != null)
                            ? conflict.getClassSubject().getSubject().getName()
                            : "Unknown Subject";

                    conflicts.add(new TeacherConflict(
                            teacher.getId(),
                            teacher.getSurname() + " " + teacher.getOtherNames(),
                            conflictingClassName,
                            slot.getDayOfWeek().name(),
                            conflict.getStartTime(),
                            conflict.getEndTime(),
                            subjectBeingPublished,
                            alreadyScheduledSubject));
                }
            }

            return conflicts;
        }

        /**
         * Detects overlapping time slots within the same day.
         * Two slots overlap if one starts before the other ends.
         */
        private void validateNoOverlaps(List<TimetableSlotEntity> slots) {
            // Group by day
            for (DayOfWeek day : DayOfWeek.values()) {
                List<TimetableSlotEntity> daySlots = slots.stream()
                        .filter(s -> s.getDayOfWeek() == day)
                        .sorted((a, b) -> a.getStartTime().compareTo(b.getStartTime()))
                        .toList();

                for (int i = 0; i < daySlots.size() - 1; i++) {
                    LocalTime currentEnd   = daySlots.get(i).getEndTime();
                    LocalTime nextStart    = daySlots.get(i + 1).getStartTime();
                    if (currentEnd.isAfter(nextStart)) {
                        throw new BusinessRuleViolationException(
                                "Overlapping slots on " + day + ": "
                                + daySlots.get(i).getStartTime() + "-" + currentEnd
                                + " overlaps with "
                                + nextStart + "-" + daySlots.get(i + 1).getEndTime());
                    }
                }
            }
        }

        /**
         * Enforces the 5-version historical cap.
         * When total versions exceed MAX_HISTORY_VERSIONS, the oldest inactive
         * version(s) are deleted. The active timetable is never deleted here.
         */
        private void pruneOldVersions(Long classSessionId, Long schoolId) {
            int total = timetableRepo.countByClassSessionIdAndSchoolId(classSessionId, schoolId);
            if (total <= MAX_HISTORY_VERSIONS) return;

            List<TimetableEntity> allByAge = timetableRepo
                    .findByClassSessionIdAndSchoolIdOrderByVersionAsc(classSessionId, schoolId);

            int toDelete = total - MAX_HISTORY_VERSIONS;
            int deleted = 0;
            for (TimetableEntity t : allByAge) {
                if (deleted >= toDelete) break;
                if (!t.isActive()) {
                    timetableRepo.delete(t);
                    deleted++;
                    log.info("Pruned timetable v{} for classSession={} (history cap)",
                            t.getVersion(), classSessionId);
                }
            }
        }
    }

    /**
     * Permanently deletes a specific historical timetable version.
     * The active timetable cannot be deleted via this command.
     */
    @Component
    @RequiredArgsConstructor
    @Transactional
    public static class DeleteTimetableVersionHandler
            implements CommandHandler<DeleteTimetableVersionCommand, Void> {

        private final JpaTimetableRepository timetableRepo;

        @Override
        public Void handle(DeleteTimetableVersionCommand cmd) {
            Long schoolId = SchoolIdInjector.require();

            TimetableEntity timetable = timetableRepo.findByIdAndSchoolId(cmd.timetableId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException("Timetable", cmd.timetableId()));

            if (timetable.isActive()) {
                throw new BusinessRuleViolationException(
                        "Cannot delete the active timetable. Publish a new timetable to replace it.");
            }

            timetableRepo.delete(timetable);
            log.info("Deleted timetable v{} id={} school={}", timetable.getVersion(),
                    cmd.timetableId(), schoolId);
            return null;
        }
    }
}
