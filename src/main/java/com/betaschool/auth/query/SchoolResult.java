package com.betaschool.auth.query;

import java.time.OffsetDateTime;
import java.util.List;

public sealed interface SchoolResult {

    record SchoolSummary(
            Long id,
            String name,
            String slug,
            String email,
            String status,
            OffsetDateTime createdAt
    ) implements SchoolResult {}

    record SchoolDetail(
            Long id,
            String name,
            String slug,
            String email,
            String phone,
            String address,
            String status,
            OffsetDateTime activatedAt,
            OffsetDateTime deactivatedAt,
            String deactivationReason,
            OffsetDateTime createdAt,
            int totalUsers
    ) implements SchoolResult {}

    record UserSummary(
            Long id,
            String email,
            String role,
            String status,
            OffsetDateTime lastLoginAt,
            OffsetDateTime createdAt
    ) implements SchoolResult {}
}
