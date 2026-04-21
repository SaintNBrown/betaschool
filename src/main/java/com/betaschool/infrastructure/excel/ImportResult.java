package com.betaschool.infrastructure.excel;

import java.util.List;

/**
 * Returned by both import endpoints.
 * Partial success is normal — valid rows are committed, invalid rows are reported.
 */
public record ImportResult(
        int totalRows,
        int successCount,
        int failureCount,
        List<RowError> errors
) {
    public record RowError(int row, String reason) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int totalRows;
        private int successCount;
        private int failureCount;
        private final List<RowError> errors = new java.util.ArrayList<>();

        public Builder totalRows(int n)    { this.totalRows    = n; return this; }
        public Builder successCount(int n) { this.successCount = n; return this; }
        public Builder failureCount(int n) { this.failureCount = n; return this; }
        public Builder error(int row, String reason) {
            errors.add(new RowError(row, reason)); return this;
        }
        public ImportResult build() {
            return new ImportResult(totalRows, successCount, failureCount,
                    java.util.Collections.unmodifiableList(errors));
        }
    }
}
