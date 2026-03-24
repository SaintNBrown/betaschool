package com.betaschool.api.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record BulkRecordResultsRequest(
        @NotEmpty @Valid List<StudentScore> scores
) {
    public record StudentScore(
            @NotNull Long studentId,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score,
            Long resultId
    ) {}
}
