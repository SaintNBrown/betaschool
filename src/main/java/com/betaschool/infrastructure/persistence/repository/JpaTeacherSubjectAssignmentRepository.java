package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TeacherSubjectAssignmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTeacherSubjectAssignmentRepository extends JpaRepository<TeacherSubjectAssignmentEntity, Long> {

    Optional<TeacherSubjectAssignmentEntity> findByClassSubjectIdAndSchoolId(Long classSubjectId, Long schoolId);
    boolean existsByClassSubjectIdAndSchoolId(Long classSubjectId, Long schoolId);

    /** Issue 7: check whether a specific teacher is assigned to a specific class-subject. */
    boolean existsByTeacherIdAndClassSubjectIdAndSchoolId(Long teacherId, Long classSubjectId, Long schoolId);

    /**
     * All subject assignments for a teacher across all sessions (school-scoped).
     * Used when filtering examinations by term — we need all assigned classSubject IDs
     * regardless of session to match against the term's exams.
     */
    @Query("""
        SELECT tsa FROM TeacherSubjectAssignmentEntity tsa
        JOIN FETCH tsa.classSubject cs
        WHERE tsa.teacher.id = :teacherId
          AND tsa.schoolId = :schoolId
        """)
    List<TeacherSubjectAssignmentEntity> findByTeacherIdAndSchoolId(
            @Param("teacherId") Long teacherId,
            @Param("schoolId") Long schoolId);

    /**
     * Issue 1+: replaces the findAll() + stream filter anti-pattern.
     * Fetches all subject assignments for a teacher in a given session, school-scoped.
     */
    @Query("""
        SELECT tsa FROM TeacherSubjectAssignmentEntity tsa
        JOIN FETCH tsa.classSubject cs
        JOIN FETCH cs.subject subj
        JOIN FETCH cs.classSession cls
        JOIN FETCH cls.clazz c
        JOIN FETCH cls.session s
        WHERE tsa.teacher.id = :teacherId
          AND s.id = :sessionId
          AND tsa.schoolId = :schoolId
        """)
    List<TeacherSubjectAssignmentEntity> findByTeacherIdAndSessionIdAndSchoolId(
            @Param("teacherId") Long teacherId,
            @Param("sessionId") Long sessionId,
            @Param("schoolId") Long schoolId);
}
