package com.betaschool.command.model;

import com.betaschool.shared.Command;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public sealed interface TeacherCommand {
    record CreateTeacherCommand(
            @NotBlank String surname,
            @NotBlank String otherNames,
            @Email @NotBlank String email
    ) implements Command<Long>, TeacherCommand {}

    record UpdateTeacherCommand(
            Long teacherId,
            @NotBlank String surname,
            @NotBlank String otherNames,
            @Email String email
    ) implements Command<Void>, TeacherCommand {}

    record AssignTeacherToClassCommand(
            Long teacherId,
            Long classSessionId,
            boolean isFormTeacher
    ) implements Command<Long>, TeacherCommand {}

    record AssignTeacherToSubjectCommand(
            Long teacherId,
            Long classSubjectId
    ) implements Command<Long>, TeacherCommand {}

    /**
     * Removes a teacher from a class-subject.
     * teacherId is optional — if provided it is validated against the current assignment.
     */
    record UnassignTeacherFromSubjectCommand(
            Long teacherId,      // optional — if null, removes whoever is assigned
            Long classSubjectId
    ) implements Command<Void>, TeacherCommand {}

    /**
     * Atomically replaces the current teacher assigned to a class-subject with a new one.
     */
    record ReassignTeacherToSubjectCommand(
            Long newTeacherId,
            Long classSubjectId
    ) implements Command<Void>, TeacherCommand {}

    /**
     * Primary-school "class teacher" assignment.
     *
     * Assigns a form teacher to ALL subjects in a class-session in one operation.
     * This covers the primary school model where the class teacher (form teacher)
     * teaches every subject in their class.
     *
     * Behaviour:
     *  - The teacher must already be assigned to the class-session as form teacher
     *    (or the caller must pass isFormTeacher=true to also assign them to the class).
     *  - Subjects that already have a different teacher assigned are SKIPPED — this
     *    handles the "specialist subject" exception (e.g. music taken by a different
     *    teacher). Those subjects are returned in the result so the caller knows.
     *  - Subjects already assigned to THIS teacher are left unchanged (idempotent).
     *  - Returns a summary of how many subjects were assigned, skipped, and already owned.
     */
    record AssignFormTeacherToAllSubjectsCommand(
            Long teacherId,
            Long classSessionId,
            boolean setAsFormTeacher   // if true, also (re)sets them as form teacher of the class
    ) implements Command<FormTeacherAssignmentResult>, TeacherCommand {}

    record FormTeacherAssignmentResult(
            int assigned,              // subjects newly assigned to this teacher
            int alreadyOwned,          // subjects already assigned to this teacher (no change)
            int skippedOtherTeacher,   // subjects that already have a different teacher (skipped)
            java.util.List<String> skippedSubjectNames  // names of skipped subjects for display
    ) {}

}
