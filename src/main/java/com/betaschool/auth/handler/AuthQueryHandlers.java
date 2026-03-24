package com.betaschool.auth.handler;

import com.betaschool.auth.query.AuthQuery.*;
import com.betaschool.auth.query.SchoolResult.*;
import com.betaschool.infrastructure.persistence.repository.auth.JpaAppUserRepository;
import com.betaschool.infrastructure.persistence.repository.auth.JpaSchoolRepository;
import com.betaschool.shared.QueryHandler;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

public class AuthQueryHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetAllSchoolsHandler implements QueryHandler<GetAllSchoolsQuery, Page<SchoolSummary>> {

        private final JpaSchoolRepository schoolRepo;
        private final TenantGuard tenantGuard;

        @Override
        public Page<SchoolSummary> handle(GetAllSchoolsQuery query) {
            tenantGuard.requireRole("SYSTEM_ADMIN");
            return schoolRepo.findAll(query.pageable())
                    .map(s -> new SchoolSummary(s.getId(), s.getName(), s.getSlug(),
                            s.getEmail(), s.getStatus().name(), s.getCreatedAt()));
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetSchoolByIdHandler implements QueryHandler<GetSchoolByIdQuery, SchoolDetail> {

        private final JpaSchoolRepository schoolRepo;
        private final JpaAppUserRepository userRepo;
        private final TenantGuard tenantGuard;

        @Override
        @Cacheable(value = "schools", key = "#query.schoolId()")
        public SchoolDetail handle(GetSchoolByIdQuery query) {
            tenantGuard.requireRole("SYSTEM_ADMIN", "SCHOOL_ADMIN");

            // School admins can only view their own school
            if (!"SYSTEM_ADMIN".equals(tenantGuard.getSchoolIdOrNull() == null ? "SYSTEM_ADMIN" : "OTHER")) {
                tenantGuard.assertBelongsToCurrentSchool(query.schoolId());
            }

            var school = schoolRepo.findById(query.schoolId())
                    .orElseThrow(() -> new ResourceNotFoundException("School", query.schoolId()));

            int userCount = userRepo.findBySchoolId(query.schoolId()).size();

            return new SchoolDetail(school.getId(), school.getName(), school.getSlug(),
                    school.getEmail(), school.getPhone(), school.getAddress(),
                    school.getStatus().name(), school.getActivatedAt(),
                    school.getDeactivatedAt(), school.getDeactivationReason(),
                    school.getCreatedAt(), userCount);
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetSchoolUsersHandler implements QueryHandler<GetSchoolUsersQuery, List<UserSummary>> {

        private final JpaAppUserRepository userRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<UserSummary> handle(GetSchoolUsersQuery query) {
            tenantGuard.requireRole("SYSTEM_ADMIN", "SCHOOL_ADMIN");
            tenantGuard.assertBelongsToCurrentSchool(query.schoolId());

            return userRepo.findBySchoolId(query.schoolId()).stream()
                    .map(u -> new UserSummary(u.getId(), u.getEmail(), u.getRole().name(),
                            u.getStatus().name(), u.getLastLoginAt(), u.getCreatedAt()))
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetActiveSchoolsHandler implements QueryHandler<GetActiveSchoolsQuery, List<SchoolSummary>> {

        private final JpaSchoolRepository schoolRepo;
        private final TenantGuard tenantGuard;

        @Override
        @Cacheable("activeSchools")
        public List<SchoolSummary> handle(GetActiveSchoolsQuery query) {
            tenantGuard.requireRole("SYSTEM_ADMIN");
            return schoolRepo.findByStatus(com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity.SchoolStatus.ACTIVE)
                    .stream()
                    .map(s -> new SchoolSummary(s.getId(), s.getName(), s.getSlug(),
                            s.getEmail(), s.getStatus().name(), s.getCreatedAt()))
                    .collect(Collectors.toList());
        }
    }
}
