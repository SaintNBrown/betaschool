package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TestScoreEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTestScoreRepository extends JpaRepository<TestScoreEntity, Long> {

    Optional<TestScoreEntity> findByExaminationIdAndStudentIdAndSchoolId(
            Long examinationId, Long studentId, Long schoolId);

    boolean existsByExaminationIdAndStudentIdAndSchoolId(
            Long examinationId, Long studentId, Long schoolId);

    /** All CA/test scores already recorded for an examination — used to pre-populate the CA score entry modal. */
    @Query("""
        SELECT ts FROM TestScoreEntity ts
        JOIN FETCH ts.student s
        WHERE ts.examination.id = :examinationId
          AND ts.schoolId = :schoolId
        """)
    List<TestScoreEntity> findAllByExaminationIdAndSchoolId(
            @Param("examinationId") Long examinationId,
            @Param("schoolId") Long schoolId);

    /**
     * All CA/test scores for a school as a flat list.
     * Used by the export — caller builds a Map<(examinationId,studentId)->score>
     * in memory, then joins to ResultEntity rows. Two queries, no N+1.
     */
    @Query("""        
        SELECT ts FROM TestScoreEntity ts
        JOIN FETCH ts.student
        JOIN FETCH ts.examination
        WHERE ts.schoolId = :schoolId
        """)
    List<TestScoreEntity> findAllForExport(@Param("schoolId") Long schoolId);
}
