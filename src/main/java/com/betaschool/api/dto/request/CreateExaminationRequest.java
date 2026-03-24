package com.betaschool.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public record CreateExaminationRequest(
        @NotNull Long termId,
        @NotNull Long classSubjectId,
        LocalDate examDate,
        LocalTime examStartTime,           // optional — time exam starts
        Integer durationMinutes,           // optional — duration in minutes
        BigDecimal testMaxScore,           // optional — defaults to 40; must sum to 100 with examMaxScore
        BigDecimal examMaxScore            // optional — defaults to 60
) {}
