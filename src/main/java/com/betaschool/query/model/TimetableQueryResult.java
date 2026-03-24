package com.betaschool.query.model;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public sealed interface TimetableQueryResult {

    /**
     * Lightweight summary of a timetable version — used in history listing.
     */
    record TimetableSummary(
            Long id,
            Long classSessionId,
            String className,
            String sessionName,
            Integer version,
            boolean isActive,
            String notes,
            OffsetDateTime createdAt
    ) implements TimetableQueryResult {}

    /**
     * Full timetable detail — slots grouped by day for easy frontend rendering.
     * Map key is the day name (e.g. "MONDAY"), value is the ordered list of slots.
     */
    record TimetableDetail(
            Long id,
            Long classSessionId,
            String className,
            String sessionName,
            Integer version,
            boolean isActive,
            String notes,
            OffsetDateTime createdAt,
            Map<String, List<SlotDetail>> schedule   // keyed by DayOfWeek name
    ) implements TimetableQueryResult {}

    record SlotDetail(
            Long id,
            String dayOfWeek,
            LocalTime startTime,
            LocalTime endTime,
            String slotType,            // SUBJECT or ACTIVITY
            Long classSubjectId,        // null for ACTIVITY
            String subjectName,         // null for ACTIVITY
            Long teacherId,             // null for ACTIVITY or unassigned subject
            String teacherName,         // null for ACTIVITY or unassigned subject
            String activityLabel,       // null for SUBJECT
            Integer sortOrder
    ) implements TimetableQueryResult {}
}
