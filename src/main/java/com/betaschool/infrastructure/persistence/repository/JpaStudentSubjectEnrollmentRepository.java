package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.StudentSubjectEnrollmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaStudentSubjectEnrollmentRepository
        extends JpaRepository<StudentSubjectEnrollmentEntity, Long> {

    Optional<StudentSubjectEnrollmentEntity> findByStudentIdAndClassSubjectIdAndSchoolId(
            Long studentId, Long classSubjectId, Long schoolId);

    boolean existsByStudentIdAndClassSubjectIdAndSchoolId(
            Long studentId, Long classSubjectId, Long schoolId);

    /**
     * All students enrolled in a specific class-subject, with student pre-loaded.
     * Used by result-entry screens — without FETCH every sse.getStudent()
     * triggers a separate SELECT (O(N) extra queries for N students).
     */
    @Query("""
        SELECT sse FROM StudentSubjectEnrollmentEntity sse
        JOIN FETCH sse.student s
        WHERE sse.classSubject.id = :classSubjectId
          AND sse.schoolId = :schoolId
        ORDER BY s.surname, s.otherNames
        """)
    List<StudentSubjectEnrollmentEntity> findByClassSubjectIdAndSchoolId(
            @Param("classSubjectId") Long classSubjectId,
            @Param("schoolId") Long schoolId);

    /**
     * All subject enrollments for a student in a class-session, with classSubject →
     * subject and teacherAssignment → teacher eagerly loaded.
     * Used by GetStudentSubjectsHandler which accesses cs.getSubject().getName()
     * and cs.getTeacherAssignment().getTeacher() per row.
     */
    @Query("""
        SELECT sse FROM StudentSubjectEnrollmentEntity sse
        JOIN FETCH sse.classSubject cs
        JOIN FETCH cs.subject subj
        LEFT JOIN FETCH cs.teacherAssignment ta
        LEFT JOIN FETCH ta.teacher t
        WHERE cs.classSession.id = :classSessionId
          AND sse.student.id = :studentId
          AND sse.schoolId = :schoolId
        ORDER BY subj.name
        """)
    List<StudentSubjectEnrollmentEntity> findByStudentIdAndClassSessionIdAndSchoolId(
            @Param("studentId") Long studentId,
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);
}