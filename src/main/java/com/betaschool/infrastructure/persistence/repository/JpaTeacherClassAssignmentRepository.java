package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TeacherClassAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTeacherClassAssignmentRepository extends JpaRepository<TeacherClassAssignmentEntity, Long> {
    Optional<TeacherClassAssignmentEntity> findByTeacherIdAndClassSessionIdAndSchoolId(
            Long teacherId, Long classSessionId, Long schoolId);
    boolean existsByTeacherIdAndClassSessionIdAndSchoolId(
            Long teacherId, Long classSessionId, Long schoolId);
    List<TeacherClassAssignmentEntity> findByClassSessionIdAndSchoolId(Long classSessionId, Long schoolId);
    Optional<TeacherClassAssignmentEntity> findByClassSessionIdAndFormTeacherTrueAndSchoolId(
            Long classSessionId, Long schoolId);
}
