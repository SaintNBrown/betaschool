package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.ClassSubjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaClassSubjectRepository extends JpaRepository<ClassSubjectEntity, Long> {

    Optional<ClassSubjectEntity> findByClassSessionIdAndSubjectIdAndSchoolId(
            Long classSessionId, Long subjectId, Long schoolId);

    boolean existsByClassSessionIdAndSubjectIdAndSchoolId(
            Long classSessionId, Long subjectId, Long schoolId);

    /**
     * Single-row lookup with subject and teacher eagerly loaded.
     * Used by handlers that call findByIdAndSchoolId then immediately access
     * cs.getSubject() and cs.getTeacherAssignment().getTeacher().
     */
    @Query("""
        SELECT cs FROM ClassSubjectEntity cs
        JOIN FETCH cs.subject subj
        LEFT JOIN FETCH cs.teacherAssignment ta
        LEFT JOIN FETCH ta.teacher t
        WHERE cs.id = :id
          AND cs.schoolId = :schoolId
        """)
    Optional<ClassSubjectEntity> findByIdAndSchoolId(
            @Param("id") Long id,
            @Param("schoolId") Long schoolId);

    /**
     * All subjects for a class-session with subject name and teacher pre-loaded.
     * Without these FETCH joins every cs.getSubject() and cs.getTeacherAssignment()
     * access in GetClassSubjectsHandler triggers a separate SELECT — O(N) extra queries.
     */
    @Query("""
        SELECT cs FROM ClassSubjectEntity cs
        JOIN FETCH cs.subject subj
        LEFT JOIN FETCH cs.teacherAssignment ta
        LEFT JOIN FETCH ta.teacher t
        WHERE cs.classSession.id = :classSessionId
          AND cs.schoolId = :schoolId
        ORDER BY subj.name
        """)
    List<ClassSubjectEntity> findByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /** Compulsory subjects only — FETCH joins included for the same reason. */
    @Query("""
        SELECT cs FROM ClassSubjectEntity cs
        JOIN FETCH cs.subject subj
        LEFT JOIN FETCH cs.teacherAssignment ta
        LEFT JOIN FETCH ta.teacher t
        WHERE cs.classSession.id = :classSessionId
          AND cs.schoolId = :schoolId
          AND cs.elective = false
        ORDER BY subj.name
        """)
    List<ClassSubjectEntity> findCompulsoryByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /** Elective subjects only — FETCH joins included. */
    @Query("""
        SELECT cs FROM ClassSubjectEntity cs
        JOIN FETCH cs.subject subj
        LEFT JOIN FETCH cs.teacherAssignment ta
        LEFT JOIN FETCH ta.teacher t
        WHERE cs.classSession.id = :classSessionId
          AND cs.schoolId = :schoolId
          AND cs.elective = true
        ORDER BY subj.name
        """)
    List<ClassSubjectEntity> findElectiveByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);
}