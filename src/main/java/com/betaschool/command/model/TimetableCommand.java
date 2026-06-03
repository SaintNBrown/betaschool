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
     */
    record PublishTimetableCommand(
            Long classSessionId,
            String notes,
            @NotEmpty @Valid List<TimetableSlotInput> slots
    ) implements Command<Long>, TimetableCommand {}

    record TimetableSlotInput(
            @NotNull String dayOfWeek,
            @NotNull LocalTime startTime,
            @NotNull LocalTime endTime,
            @NotNull String slotType,
            Long classSubjectId,
            String activityLabel,
            Integer sortOrder
    ) {}

    record DeleteTimetableVersionCommand(
            Long timetableId
    ) implements Command<Void>, TimetableCommand {}

    /**
     * Generates a complete weekly timetable for a class-session using a constraint
     * satisfaction solver, then publishes it via the existing PublishTimetableHandler.
     *
     * Global activities appear on EVERY operating day at the SAME subject-slot
     * position (e.g. Break always after the 4th subject slot). Their clock time
     * may vary per day due to day-specific slot replacements, but their position
     * is fixed across the week.
     *
     * Day-specific activities appear ONLY on specific days and REPLACE subject
     * slots. They are expressed as "replaces N subject slots" (e.g. replaces 2 slots).
     */
    record GenerateTimetableCommand(
            Long classSessionId,

            /** Days the school operates. */
            @NotEmpty List<String> operatingDays,

            /** Duration of each subject period in minutes. */
            @NotNull Integer slotDurationMinutes,

            /** School day start time — e.g. "08:00". */
            @NotNull LocalTime schoolStartTime,

            /**
             * Optional school closing time. When provided, ensures no day exceeds
             * this time. When null, days can extend as needed.
             */
            LocalTime schoolClosingTime,

            /**
             * Global activities — appear on EVERY operating day at the same
             * subject-slot position across all days.
             */
            List<GlobalActivitySpec> globalActivities,

            /**
             * Day-specific activities — appear ONLY on specified days and
             * REPLACE subject slots on those days.
             */
            List<DaySpecificActivitySpec> daySpecificActivities,

            /** Per-teacher unavailable days. */
            List<TeacherUnavailability> teacherUnavailableDays,

            /** How many subject periods each class-subject gets per week. */
            @NotEmpty List<SubjectFrequency> subjectFrequencies,

            /** Optional note stored on the generated timetable. */
            String notes
    ) implements Command<Long>, TimetableCommand {

        /**
         * Global activity — appears on EVERY operating day at the same
         * subject-slot position.
         *
         * afterSlotNumber:
         *   0 = before any subject slots (first)
         *   1..N = after the Nth subject slot
         *   Integer.MAX_VALUE = after all subject slots (last)
         *
         * durationSlots: how many subject-slot durations this activity occupies.
         *   e.g. 0.5 for a 20min break when slotDurationMinutes=40
         *        1.0 for a 40min assembly
         *        2.0 for an 80min activity
         */
        public record GlobalActivitySpec(
                @NotNull String label,
                @NotNull Integer afterSlotNumber,
                @NotNull Double durationSlots
        ) {}

        /**
         * Day-specific activity — appears ONLY on specified days and REPLACES
         * subject slots on those days.
         *
         * afterSlotNumber: position relative to REMAINING subject slots
         *   (after removing previously placed day-specific activities)
         *
         * replacesSlots: how many subject slot positions this activity consumes.
         *   Example: replacesSlots=2 means two consecutive subject slots are
         *   replaced by this activity.
         *
         * onlyOnDays: which days this activity appears on (must be non-empty)
         *
         * isLastOfDay: when true, always placed at end of day regardless of
         *   afterSlotNumber. Useful for dismissal or end-of-day routines.
         */
        public record DaySpecificActivitySpec(
                @NotNull String label,
                @NotEmpty List<String> onlyOnDays,
                @NotNull Integer afterSlotNumber,
                @NotNull Integer replacesSlots,
                Boolean isLastOfDay
        ) {
            public boolean isLast() {
                return Boolean.TRUE.equals(isLastOfDay);
            }
        }

        /** Marks a teacher as unavailable on specific days. */
        public record TeacherUnavailability(
                @NotNull Long teacherId,
                @NotEmpty List<String> unavailableDays
        ) {}

        /**
         * How many periods per week a specific class-subject should receive.
         *
         * forceDouble: only relevant when periodsPerWeek == 2.
         *   false (default) — two SINGLES on different days
         *   true — one DOUBLE on one day
         *
         * For periodsPerWeek >= 3 the generator always maximises doubles
         * (max doubles, remaining singles) regardless of this flag.
         */
        public record SubjectFrequency(
                @NotNull Long classSubjectId,
                @NotNull Integer periodsPerWeek,
                Boolean forceDouble
        ) {}
    }
}