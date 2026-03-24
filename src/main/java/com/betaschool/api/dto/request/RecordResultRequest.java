package com.betaschool.api.dto.request;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record RecordResultRequest(
        @NotNull Long studentId,
        @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score
) {}
