package com.betaschool.command.model;

import com.betaschool.shared.Command;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public sealed interface AcademicCommand {

    // ── Session ──────────────────────────────────────────────────

    record CreateSessionCommand(
            @NotBlank String sessionName,
            @NotNull LocalDate startDate,
            @NotNull LocalDate closingDate
    ) implements Command<Long>, AcademicCommand {}

    record UpdateSessionCommand(
            Long sessionId,
            @NotBlank String sessionName,
            @NotNull LocalDate startDate,
            @NotNull LocalDate closingDate
    ) implements Command<Void>, AcademicCommand {}

    /**
     * Issue 5: Designates one session as the school's current/active academic session.
     * Any previously current session for the same school is automatically unset.
     */
    record SetCurrentSessionCommand(
            Long sessionId
    ) implements Command<Void>, AcademicCommand {}

    // ── Class ────────────────────────────────────────────────────

    record CreateClassCommand(
            @NotBlank String name
    ) implements Command<Long>, AcademicCommand {}

    // ── Class-Session Link ────────────────────────────────────────

    record OpenClassInSessionCommand(
            Long classId,
            Long sessionId
    ) implements Command<Long>, AcademicCommand {}

    // ── Subject ──────────────────────────────────────────────────

    record CreateSubjectCommand(
            @NotBlank String name
    ) implements Command<Long>, AcademicCommand {}

    // ── Class-Subject Link ────────────────────────────────────────

    record AddSubjectToClassCommand(
            Long classSessionId,
            Long subjectId,
            boolean isElective
    ) implements Command<Long>, AcademicCommand {}

    /**
     * Toggles a class-subject between compulsory and elective.
     * When switching compulsory → elective: existing student enrollments are retained
     * (students already taking it keep it; they can later be unenrolled manually).
     * When switching elective → compulsory: all students currently enrolled in the
     * class-session who are not yet enrolled in this subject are auto-enrolled.
     */
    record UpdateClassSubjectTypeCommand(
            Long classSubjectId,
            boolean isElective
    ) implements Command<Void>, AcademicCommand {}

    // ── Term ─────────────────────────────────────────────────────

    record CreateTermCommand(
            Long classSessionId,
            Integer termNumber
    ) implements Command<Long>, AcademicCommand {}
}
