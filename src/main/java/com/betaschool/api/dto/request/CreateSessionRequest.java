package com.betaschool.api.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// ── Session ──────────────────────────────────────────────────────────

public record CreateSessionRequest(
        @NotBlank String sessionName,
        @NotNull LocalDate startDate,
        @NotNull LocalDate closingDate
) {}
