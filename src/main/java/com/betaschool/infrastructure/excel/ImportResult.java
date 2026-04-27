package com.betaschool.infrastructure.excel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Returned by all import endpoints.
 * Partial success is normal — valid rows commit, invalid rows are reported.
 *
 * When createAccounts=true is used on the student import endpoint,
 * createdAccounts is populated with one entry per successfully created login.
 * This field is null (omitted from JSON via @JsonInclude on ApiResponse) when
 * accounts were not requested.
 */
public record ImportResult(
        int totalRows,
        int successCount,
        int failureCount,
        List<RowError> errors,
        List<CreatedAccount> createdAccounts  // null unless createAccounts=true
) {
    public record RowError(int row, String reason) {}

    /**
     * Temporary credentials for a newly created student login account.
     * Returned once in the import response — not stored in plaintext anywhere.
     * The student must change their password on first login.
     */
    public record CreatedAccount(String email, String temporaryPassword) {}

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int totalRows;
        private int successCount;
        private int failureCount;
        private final List<RowError>      errors          = new ArrayList<>();
        private final List<CreatedAccount> createdAccounts = new ArrayList<>();

        public Builder totalRows(int n)    { totalRows    = n; return this; }
        public Builder successCount(int n) { successCount = n; return this; }
        public Builder failureCount(int n) { failureCount = n; return this; }

        public Builder error(int row, String reason) {
            errors.add(new RowError(row, reason)); return this;
        }
        public Builder account(String email, String pw) {
            createdAccounts.add(new CreatedAccount(email, pw)); return this;
        }

        public ImportResult build() {
            return new ImportResult(
                    totalRows, successCount, failureCount,
                    Collections.unmodifiableList(errors),
                    createdAccounts.isEmpty() ? null
                            : Collections.unmodifiableList(createdAccounts));
        }
    }
}
