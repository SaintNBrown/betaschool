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
    Optional<ClassSubjectEntity> findByIdAndSchoolId(Long id, Long schoolId);
    List<ClassSubjectEntity> findByClassSessionIdAndSchoolId(Long classSessionId, Long schoolId);

    @Query("SELECT cs FROM ClassSubjectEntity cs " +
           "WHERE cs.classSession.id = :classSessionId " +
           "AND cs.schoolId = :schoolId AND cs.elective = false")
    List<ClassSubjectEntity> findCompulsoryByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /** Returns only elective subjects — used to populate the student elective enrollment list. */
    @Query("SELECT cs FROM ClassSubjectEntity cs " +
           "WHERE cs.classSession.id = :classSessionId " +
           "AND cs.schoolId = :schoolId AND cs.elective = true")
    List<ClassSubjectEntity> findElectiveByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /**
     * All class-subjects for a school with subject and teacher pre-loaded.
     * Used by the data export for class_subjects.csv.
     */
    @Query("""
        SELECT cs FROM ClassSubjectEntity cs
        JOIN FETCH cs.classSession sess
        JOIN FETCH cs.subject subj
        LEFT JOIN FETCH cs.teacherAssignment ta
        LEFT JOIN FETCH ta.teacher t
        WHERE cs.schoolId = :schoolId
        ORDER BY sess.id, subj.name
        """)
    List<ClassSubjectEntity> findAllForExport(@Param("schoolId") Long schoolId);
}
