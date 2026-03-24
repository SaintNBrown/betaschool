package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.repository.JpaClassSessionRepository;
import com.betaschool.query.model.AcademicQuery.GetClassSessionsQuery;
import com.betaschool.query.model.AcademicQueryResult.ClassSessionSummary;
import com.betaschool.shared.QueryHandler;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetClassSessionsHandler
        implements QueryHandler<GetClassSessionsQuery, List<ClassSessionSummary>> {

    private final JpaClassSessionRepository classSessionRepo;
    private final TenantGuard tenantGuard;

    @Override
    public List<ClassSessionSummary> handle(GetClassSessionsQuery query) {
        Long schoolId = tenantGuard.requireSchoolId();
        return classSessionRepo
                .findBySessionIdAndSchoolIdWithDetails(query.sessionId(), schoolId).stream()
                .map(cs -> new ClassSessionSummary(
                        cs.getId(), cs.getClazz().getId(), cs.getClazz().getName(),
                        cs.getSession().getId(), cs.getSession().getSessionName()))
                .collect(Collectors.toList());
    }
}
