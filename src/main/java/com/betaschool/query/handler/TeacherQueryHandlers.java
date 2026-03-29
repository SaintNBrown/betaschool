package com.betaschool.query.handler;

import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.repository.JpaTeacherClassAssignmentRepository;
import com.betaschool.infrastructure.persistence.repository.JpaTeacherRepository;
import com.betaschool.infrastructure.persistence.repository.JpaTeacherSubjectAssignmentRepository;
import com.betaschool.query.model.TeacherQuery.GetAllTeachersQuery;
import com.betaschool.query.model.TeacherQuery.GetTeacherByIdQuery;
import com.betaschool.query.model.TeacherQuery.GetTeacherClassesQuery;
import com.betaschool.query.model.TeacherQuery.GetTeacherSubjectsQuery;
import com.betaschool.query.model.TeacherQueryResult.TeacherClassItem;
import com.betaschool.query.model.TeacherQueryResult.TeacherDetail;
import com.betaschool.query.model.TeacherQueryResult.TeacherSubjectItem;
import com.betaschool.query.model.TeacherQueryResult.TeacherSummary;
import com.betaschool.shared.QueryHandler;
import com.betaschool.shared.exception.ResourceNotFoundException;
import com.betaschool.tenant.context.TenantContext;
import com.betaschool.tenant.context.TenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

public class TeacherQueryHandlers {

    @Component
    @RequiredArgsConstructor
    @Transactional(readOnly = true)
    public static class GetAllTeachersHandler implements QueryHandler<GetAllTeachersQuery, List<TeacherSummary>> {

        private final JpaTeacherRepository teacherRepo;
        private final com.betaschool.infrastructure.persistence.repository.auth.JpaUserProfileRepository profileRepo;
        private final com.betaschool.infrastructure.persistence.repository.auth.JpaAppUserRepository userRepo;
        private final TenantGuard tenantGuard;

        @Override
        public List<TeacherSummary> handle(GetAllTeachersQuery query) {
            Long schoolId = tenantGuard.requireSchoolId();
            tenantGuard.requireRole("SCHOOL_ADMIN", "SYSTEM_ADMIN");
            return teacherRepo.findBySchoolId(schoolId).stream()
                    .map(t -> {
                        // Look up the linked AppUser account via UserProfile
                        var profileOpt = profileRepo.findByProfileIdAndProfileType(
                                t.getId(),
                                UserProfileEntity.ProfileType.TEACHER);
                        String status = "NO_ACCOUNT";
                        Long userId = null;
                        if (profileOpt.isPresent()) {
                            var appUser = profileOpt.get().getUser();
                            status = appUser.getStatus().name();
                            userId = appUser.getId();
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
