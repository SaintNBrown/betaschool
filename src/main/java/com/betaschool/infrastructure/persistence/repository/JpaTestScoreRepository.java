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
}
