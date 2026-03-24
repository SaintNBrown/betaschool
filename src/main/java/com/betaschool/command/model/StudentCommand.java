package com.betaschool.command.model;

import com.betaschool.shared.Command;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public sealed interface StudentCommand {

    record CreateStudentCommand(
            @NotBlank String surname,
            @NotBlank String otherNames,
            @Email String email
    ) implements Command<Long>, StudentCommand {}

    record UpdateStudentCommand(
            Long studentId,
            @NotBlank String surname,
            @NotBlank String otherNames,
            @Email String email
    ) implements Command<Void>, StudentCommand {}

    record EnrollStudentInClassCommand(
            Long studentId,
            Long classSessionId
    ) implements Command<Long>, StudentCommand {}

    record EnrollStudentInSubjectCommand(
            Long studentId,
            Long classSubjectId
    ) implements Command<Long>, StudentCommand {}

    record UnenrollStudentFromSubjectCommand(
            Long studentId,
            Long classSubjectId
    ) implements Command<Void>, StudentCommand {}
}
