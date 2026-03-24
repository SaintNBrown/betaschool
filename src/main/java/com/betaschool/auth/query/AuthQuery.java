package com.betaschool.auth.query;

import com.betaschool.shared.Query;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;

public sealed interface AuthQuery {

    record GetAllSchoolsQuery(
            Pageable pageable
    ) implements Query<org.springframework.data.domain.Page<SchoolResult.SchoolSummary>>, AuthQuery {}

    record GetSchoolByIdQuery(Long schoolId)
            implements Query<SchoolResult.SchoolDetail>, AuthQuery {}

    record GetSchoolUsersQuery(Long schoolId)
            implements Query<List<SchoolResult.UserSummary>>, AuthQuery {}

    record GetActiveSchoolsQuery()
            implements Query<List<SchoolResult.SchoolSummary>>, AuthQuery {}
}
