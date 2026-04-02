package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.repository.*;
import com.betaschool.query.model.TeacherQuery.*;
import com.betaschool.query.model.TeacherQueryResult.*;
import com.betaschool.shared.QueryHandler;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TeacherQueryHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetAllTeachersHandler implements QueryHandler<GetAllTeachersQuery, List<TeacherSummary>> {

        private final JpaTeacherRepository teacherRepo;
        private final com.betaschool.infrastructure.persistence.repository.auth.JpaUserProfileRepository profileRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TeacherSummary> handle(GetAllTeachersQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");

            List<com.betaschool.infrastructure.persistence.entity.TeacherEntity> teachers =
                    teacherRepo.findBySchoolId(schoolId);
            if (teachers.isEmpty()) return List.of();

            // Batch-load all teacher account profiles in ONE query instead of
            // calling findByProfileIdAndProfileType() once per teacher (N+1).
            List<Long> teacherIds = teachers.stream()
                    .map(com.betaschool.infrastructure.persistence.entity.TeacherEntity::getId)
                    .collect(Collectors.toList());

            Map<Long, UserProfileEntity> profileByTeacherId =
                    profileRepo.findByProfileIdInAndProfileType(
                                    teacherIds,
                                    com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity.ProfileType.TEACHER)
                            .stream()
                            .collect(Collectors.toMap(
                                    com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity::getProfileId,
                                    p -> p));

            return teachers.stream()
                    .map(t -> {
                        var profile = profileByTeacherId.get(t.getId());
                        String status = "NO_ACCOUNT";
                        Long userId = null;
                        if (profile != null) {
                            status = profile.getUser().getStatus().name();
                            userId = profile.getUser().getId();
                        }
                        return new TeacherSummary(t.getId(), t.getSurname(), t.getOtherNames(),
                                t.getEmail(), status, userId);
                    })
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTeacherByIdHandler implements QueryHandler<GetTeacherByIdQuery, TeacherDetail> {

        private final JpaTeacherRepository teacherRepo;
        private final TenantGuard tenantGuard;

        @Override
        public TeacherDetail handle(GetTeacherByIdQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            // Issue 1: TEACHER role may only fetch their own profile
            if (TenantContext.isTeacher()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.teacherId(), UserProfileEntity.ProfileType.TEACHER);
            }
            return teacherRepo.findByIdAndSchoolId(query.teacherId(), schoolId)
                    .map(t -> new TeacherDetail(t.getId(), t.getSurname(), t.getOtherNames(), t.getEmail()))
                    .orElseThrow(() -> new ResourceNotFoundException("Teacher", query.teacherId()));
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTeacherClassesHandler
            implements QueryHandler<GetTeacherClassesQuery, List<TeacherClassItem>> {

        private final JpaTeacherClassAssignmentRepository assignmentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TeacherClassItem> handle(GetTeacherClassesQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            // Issue 1: TEACHER may only see their own class assignments
            if (TenantContext.isTeacher()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.teacherId(), UserProfileEntity.ProfileType.TEACHER);
            }
            return assignmentRepo
                    .findByClassSessionIdAndSchoolId(query.sessionId(), schoolId).stream()
                    .filter(a -> a.getTeacher().getId().equals(query.teacherId()))
                    .map(a -> new TeacherClassItem(
                            a.getClassSession().getId(),
                            a.getClassSession().getClazz().getName(),
                            a.getClassSession().getSession().getSessionName(),
                            a.isFormTeacher()))
                    .collect(Collectors.toList());
        }
    }

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetTeacherSubjectsHandler
            implements QueryHandler<GetTeacherSubjectsQuery, List<TeacherSubjectItem>> {

        private final JpaTeacherSubjectAssignmentRepository assignmentRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TeacherSubjectItem> handle(GetTeacherSubjectsQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            // Issue 1: TEACHER may only view their own subject assignments
            if (TenantContext.isTeacher()) {
                tenantGuard.assertOwnerOrSchoolAdmin(query.teacherId(), UserProfileEntity.ProfileType.TEACHER);
            }
            // Issue 1 fix: use scoped JPQL query instead of findAll() + stream filter
            return assignmentRepo.findByTeacherIdAndSessionIdAndSchoolId(
                            query.teacherId(), query.sessionId(), schoolId)
                    .stream()
                    .map(a -> {
                        var cs = a.getClassSubject();
                        return new TeacherSubjectItem(cs.getId(), cs.getSubject().getName(),
                                cs.getClassSession().getClazz().getName(),
                                cs.getClassSession().getSession().getSessionName(),
                                cs.isElective());
                    })
                    .collect(Collectors.toList());
        }
    }
}