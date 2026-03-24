package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.StudentSubjectEnrollmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaStudentSubjectEnrollmentRepository extends JpaRepository<StudentSubjectEnrollmentEntity, Long> {

    Optional<StudentSubjectEnrollmentEntity> findByStudentIdAndClassSubjectIdAndSchoolId(
            Long studentId, Long classSubjectId, Long schoolId);
    boolean existsByStudentIdAndClassSubjectIdAndSchoolId(
            Long studentId, Long classSubjectId, Long schoolId);

    /** All enrollments for a specific class-subject — used to build the result-entry student list. */
    @Query("SELECT sse FROM StudentSubjectEnrollmentEntity sse " +
           "WHERE sse.classSubject.id = :classSubjectId " +
           "AND sse.schoolId = :schoolId")
    List<StudentSubjectEnrollmentEntity> findByClassSubjectIdAndSchoolId(
            @Param("classSubjectId") Long classSubjectId,
            @Param("schoolId") Long schoolId);

    @Query("SELECT sse FROM StudentSubjectEnrollmentEntity sse " +
           "JOIN sse.classSubject cs " +
           "WHERE cs.classSession.id = :classSessionId " +
           "AND sse.student.id = :studentId " +
           "AND sse.schoolId = :schoolId")
    List<StudentSubjectEnrollmentEntity> findByStudentIdAndClassSessionIdAndSchoolId(
            @Param("studentId") Long studentId,
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);
}
