package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.entity.TeacherEntity;
import com.betaschool.infrastructure.persistence.entity.TimetableEntity;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity;
import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity.SlotType;
import com.betaschool.infrastructure.persistence.repository.JpaTimetableRepository;
import com.betaschool.infrastructure.persistence.repository.JpaTimetableSlotRepository;
import com.betaschool.query.model.TimetableQuery.GetActiveTimetableQuery;
import com.betaschool.query.model.TimetableQuery.GetTimetableByIdQuery;
import com.betaschool.query.model.TimetableQuery.GetTimetableConflictsQuery;
import com.betaschool.query.model.TimetableQuery.GetTimetableHistoryQuery;
import com.betaschool.query.model.TimetableQueryResult.SlotDetail;
import com.betaschool.query.model.TimetableQueryResult.TeacherConflict;
import com.betaschool.query.model.TimetableQueryResult.TimetableDetail;
import com.betaschool.query.model.TimetableQueryResult.TimetableSummary;
import com.betaschool.shared.QueryHandler;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TimetableQueryHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetActiveTimetableHandler
            implements QueryHandler<GetActiveTimetableQuery, TimetableDetail> {

        private final JpaTimetableRepository timetableRepo;
        private final JpaTimetableSlotRepository slotRepo;
        private final TenantGuard tenantGuard;

        @Override
        public TimetableDetail handle(GetActiveTimetableQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            TimetableEntity timetable = timetableRepo
                    .findByClassSessionIdAndSchoolIdAndActiveTrue(query.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No active timetable found for class-session id="
                            + query.classSessionId()
                            + ". Publish a timetable first."));

            return buildDetail(timetable, slotRepo, schoolId);
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTimetableHistoryHandler
            implements QueryHandler<GetTimetableHistoryQuery, List<TimetableSummary>> {

        private final JpaTimetableRepository timetableRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TimetableSummary> handle(GetTimetableHistoryQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            return timetableRepo
                    .findByClassSessionIdAndSchoolIdOrderByVersionDesc(
                            query.classSessionId(), schoolId)
                    .stream()
                    .map(t -> new TimetableSummary(
                            t.getId(),
                            t.getClassSession().getId(),
                            t.getClassSession().getClazz().getName(),
                            t.getClassSession().getSession().getSessionName(),
                            t.getVersion(),
                            t.isActive(),
                            t.getNotes(),
                            t.getCreatedAt()))
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTimetableByIdHandler
            implements QueryHandler<GetTimetableByIdQuery, TimetableDetail> {

        private final JpaTimetableRepository timetableRepo;
        private final JpaTimetableSlotRepository slotRepo;
        private final TenantGuard tenantGuard;

        @Override
        public TimetableDetail handle(GetTimetableByIdQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            TimetableEntity timetable = timetableRepo
                    .findByIdAndSchoolId(query.timetableId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Timetable", query.timetableId()));

            return buildDetail(timetable, slotRepo, schoolId);
        }
    }

    /**
     * Shared helper: loads slots, resolves teacher from the existing
     * teacher_subject_assignment, and builds the schedule map grouped by day.
     */
    static TimetableDetail buildDetail(TimetableEntity timetable,
                                        JpaTimetableSlotRepository slotRepo,
                                        Long schoolId) {
        List<TimetableSlotEntity> slots = slotRepo
                .findByTimetableIdAndSchoolIdOrdered(timetable.getId(), schoolId);

        // Group by day, preserving DayOfWeek order
        Map<String, List<SlotDetail>> schedule = new LinkedHashMap<>();
        for (TimetableSlotEntity.DayOfWeek day : TimetableSlotEntity.DayOfWeek.values()) {
            List<SlotDetail> daySlots = slots.stream()
                    .filter(s -> s.getDayOfWeek() == day)
                    .map(TimetableQueryHandlers::toSlotDetail)
                    .collect(Collectors.toList());
            if (!daySlots.isEmpty()) {
                schedule.put(day.name(), daySlots);
            }
        }

        return new TimetableDetail(
                timetable.getId(),
                timetable.getClassSession().getId(),
                timetable.getClassSession().getClazz().getName(),
                timetable.getClassSession().getSession().getSessionName(),
                timetable.getVersion(),
                timetable.isActive(),
                timetable.getNotes(),
                timetable.getCreatedAt(),
                schedule);
    }

    private static SlotDetail toSlotDetail(TimetableSlotEntity slot) {
        if (slot.getSlotType() == SlotType.SUBJECT && slot.getClassSubject() != null) {
            Long teacherId = null;
            String teacherName = "Unassigned";
            // Resolve teacher from the existing teacher_subject_assignment
            if (slot.getClassSubject().getTeacherAssignment() != null) {
                TeacherEntity teacher = slot.getClassSubject().getTeacherAssignment().getTeacher();
                teacherId = teacher.getId();
                teacherName = teacher.getSurname() + " " + teacher.getOtherNames();
            }
            return new SlotDetail(
                    slot.getId(),
                    slot.getDayOfWeek().name(),
                    slot.getStartTime(),
                    slot.getEndTime(),
                    slot.getSlotType().name(),
                    slot.getClassSubject().getId(),
                    slot.getClassSubject().getSubject().getName(),
                    teacherId,
                    teacherName,
                    null,
                    slot.getSortOrder());
        } else {
            // ACTIVITY slot
            return new SlotDetail(
                    slot.getId(),
                    slot.getDayOfWeek().name(),
                    slot.getStartTime(),
                    slot.getEndTime(),
                    slot.getSlotType().name(),
                    null, null, null, null,
                    slot.getActivityLabel(),
                    slot.getSortOrder());
        }
    }

    /**
     * Returns all cross-class teacher conflicts for the active timetable of a
     * class-session without throwing. Intended for the pre-publish conflict check
     * endpoint so the frontend can surface issues before the admin attempts to publish.
     *
     * Returns an empty list when there are no conflicts (the happy path).
     */
    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTimetableConflictsHandler
            implements QueryHandler<GetTimetableConflictsQuery, List<TeacherConflict>> {

        private final JpaTimetableRepository timetableRepo;
        private final JpaTimetableSlotRepository slotRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TeacherConflict> handle(GetTimetableConflictsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();

            TimetableEntity timetable = timetableRepo
                    .findByClassSessionIdAndSchoolIdAndActiveTrue(query.classSessionId(), schoolId)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No active timetable for class-session id=" + query.classSessionId()
                            + ". Publish a timetable first."));

            List<TimetableSlotEntity> slots = slotRepo
                    .findByTimetableIdAndSchoolIdOrdered(timetable.getId(), schoolId);

            List<TeacherConflict> conflicts = new ArrayList<>();

            for (TimetableSlotEntity slot : slots) {
                if (slot.getSlotType() != SlotType.SUBJECT) continue;
                if (slot.getClassSubject() == null) continue;
                if (slot.getClassSubject().getTeacherAssignment() == null) continue;

                TeacherEntity teacher = slot.getClassSubject().getTeacherAssignment().getTeacher();
                String subjectInThisTimetable = slot.getClassSubject().getSubject().getName();

                List<TimetableSlotEntity> conflicting = slotRepo.findConflictingTeacherSlots(
                        teacher.getId(),
                        slot.getDayOfWeek(),
                        slot.getStartTime(),
                        slot.getEndTime(),
                        query.classSessionId(),
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
                            subjectInThisTimetable,
                            alreadyScheduledSubject));
                }
            }

            return conflicts;
        }
    }
}
