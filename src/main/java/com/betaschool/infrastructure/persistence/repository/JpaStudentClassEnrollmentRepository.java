package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.StudentClassEnrollmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaStudentClassEnrollmentRepository extends JpaRepository<StudentClassEnrollmentEntity, Long> {

    Optional<StudentClassEnrollmentEntity> findByStudentIdAndClassSessionIdAndSchoolId(
            Long studentId, Long classSessionId, Long schoolId);

    boolean existsByStudentIdAndClassSessionIdAndSchoolId(
            Long studentId, Long classSessionId, Long schoolId);

    List<StudentClassEnrollmentEntity> findByClassSessionIdAndSchoolId(Long classSessionId, Long schoolId);

    /**
     * One-class-per-session enforcement: checks if a student is already enrolled
     * in ANY class-session belonging to the given session.
     */
    @Query("SELECT COUNT(e) > 0 FROM StudentClassEnrollmentEntity e " +
           "WHERE e.student.id = :studentId " +
           "AND e.classSession.session.id = :sessionId " +
           "AND e.schoolId = :schoolId")
    boolean existsByStudentIdAndSessionIdAndSchoolId(
            @Param("studentId") Long studentId,
            @Param("sessionId") Long sessionId,
            @Param("schoolId") Long schoolId);
}
