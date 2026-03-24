package com.betaschool.api.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CreateStudentRequest(
        @NotBlank String surname,
        @NotBlank String otherNames,
        @Email String email
) {}
