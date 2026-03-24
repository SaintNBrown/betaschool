package com.betaschool.command.model;

import com.betaschool.shared.Command;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;
import java.util.List;

public sealed interface TimetableCommand {

    /**
     * Publishes a new timetable for a class-session.
     *
     * This is an all-or-nothing replace operation: the entire weekly schedule
     * is submitted as a list of slots. The previous active timetable is
     * deactivated, a new version is created, and the cap of 5 historical
     * versions is enforced by deleting the oldest if exceeded.
     *
     * Slot validation rules enforced in the handler:
     *  - end_time must be after start_time (also enforced at DB level)
     *  - SUBJECT slots must reference a class_subject belonging to this class-session
     *  - ACTIVITY slots must have a non-blank activity_label
     *  - No two slots on the same day may have overlapping time ranges
     */
    record PublishTimetableCommand(
            Long classSessionId,
            String notes,                      // optional — describe what changed
            @NotEmpty @Valid List<TimetableSlotInput> slots
    ) implements Command<Long>, TimetableCommand {}

    record TimetableSlotInput(
            @NotNull String dayOfWeek,         // MONDAY..SUNDAY
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull String slotType,          // SUBJECT or ACTIVITY
            Long classSubjectId,               // required when slotType = SUBJECT
            String activityLabel,              // required when slotType = ACTIVITY
            Integer sortOrder                  // optional; defaults to 0
    ) {}

    /**
     * Permanently deletes a specific historical timetable version.
     * The active timetable cannot be deleted this way — publish a new one to replace it.
     */
    record DeleteTimetableVersionCommand(
            Long timetableId
    ) implements Command<Void>, TimetableCommand {}
}
