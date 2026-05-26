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

    /**
     * Generates a complete weekly timetable for a class-session using a constraint
     * satisfaction solver, then publishes it via the existing PublishTimetableHandler.
     *
     * The generator respects:
     *  - Max 2 consecutive slots of the same subject per day
     *  - Teacher-transition constraint: consecutive subject groups must not share a teacher
     *  - Teacher unavailable days
     *  - School non-operating days
     *  - Cross-class teacher conflicts (against other active timetables)
     *  - Activity placement with consistent position across all operating days
     *  - Consistent subject-slot duration across all days
     *  - Per-subject frequency (periodsPerWeek)
     */
    record GenerateTimetableCommand(
            Long classSessionId,

            /** Days the school operates. Omit to use MON–FRI. */
            @NotEmpty List<String> operatingDays,

            /** Duration of each subject period in minutes. */
            @NotNull Integer slotDurationMinutes,

            /** School day start time — e.g. "08:00". */
            @NotNull LocalTime schoolStartTime,

            /**
             * Activity slots to embed in every operating day.
             * Position is anchored by afterSlotNumber (0 = before any subject).
             */
            List<ActivitySpec> activities,

            /** Per-teacher unavailable days. */
            List<TeacherUnavailability> teacherUnavailableDays,

            /** How many subject periods each class-subject gets per week. */
            @NotEmpty List<SubjectFrequency> subjectFrequencies,

            /** Optional note stored on the generated timetable. */
            String notes,

            /** Closing time for the school*/
            LocalTime schoolClosingTime
    ) implements Command<Long>, TimetableCommand {

        /**
         * Defines an activity block in the day.
         *
         * afterSlotNumber: position anchor.
         *   0              = before all subject slots (first in day)
         *   N              = after the Nth subject slot
         *   Integer.MAX    = after all subject slots (last)
         *
         * onlyOnDays: optional. When null or empty the activity appears on every
         *   operating day. When specified (e.g. ["THURSDAY"]) it only appears on
         *   those days — all other days skip it. This supports school-specific
         *   day patterns such as sports on Thursday mornings or vocational
         *   activities on Friday afternoons.
         *
         * The subject slot COUNT is always consistent across all days regardless
         * of which activities appear on a given day. Day-specific activities
         * only shift the clock times of the subject slots on that day.
         */
        public record ActivitySpec(
                @NotNull String label,
                @NotNull Integer durationMinutes,
                @NotNull Integer afterSlotNumber,
                /** Null or empty = all operating days. */
                List<String> onlyOnDays,
                boolean isLastOfDay
        ) {}

        /** Marks a teacher as unavailable on specific days. */
        public record TeacherUnavailability(
                @NotNull Long teacherId,
                @NotEmpty List<String> unavailableDays
        ) {}

        /**
         * How many periods per week a specific class-subject should receive.
         *
         * forceDouble: only relevant when periodsPerWeek == 2.
         *   false (default) — the two periods are placed as two SINGLES on
         *     different days, alternating across the week for even spread.
         *   true  — the two periods are placed as one DOUBLE (consecutive
         *     slots on the same day), useful for subjects that benefit from
         *     a longer uninterrupted block (e.g. practicals, art).
         *
         * For periodsPerWeek >= 3 the generator always maximises doubles
         * (max doubles, remaining singles) regardless of this flag.
         */
        public record SubjectFrequency(
                @NotNull Long classSubjectId,
                @NotNull Integer periodsPerWeek,
                /** Only applies when periodsPerWeek == 2. Default false. */
                Boolean forceDouble
        ) {}
    }
}
