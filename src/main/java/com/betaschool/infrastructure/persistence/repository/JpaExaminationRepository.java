package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.ExaminationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaExaminationRepository extends JpaRepository<ExaminationEntity, Long> {

    Optional<ExaminationEntity> findByTermIdAndClassSubjectIdAndSchoolId(
            Long termId, Long classSubjectId, Long schoolId);
    boolean existsByTermIdAndClassSubjectIdAndSchoolId(
            Long termId, Long classSubjectId, Long schoolId);
    Optional<ExaminationEntity> findByIdAndSchoolId(Long id, Long schoolId);

    @Query("SELECT e FROM ExaminationEntity e " +
           "JOIN FETCH e.classSubject cs " +
           "JOIN FETCH cs.subject " +
           "WHERE e.term.id = :termId AND e.schoolId = :schoolId")
    List<ExaminationEntity> findByTermIdAndSchoolIdWithSubjectDetails(
            @Param("termId") Long termId,
            @Param("schoolId") Long schoolId);

    /**
     * Returns all examinations on a given date within the same term.
     * Used in the handler to detect time-window clashes in Java
     * (avoids non-portable JPQL time arithmetic functions).
     */
    @Query("SELECT e FROM ExaminationEntity e " +
           "WHERE e.term.id = :termId " +
           "AND e.schoolId = :schoolId " +
           "AND e.examDate = :examDate " +
           "AND e.examStartTime IS NOT NULL " +
           "AND e.durationMinutes IS NOT NULL")
    List<ExaminationEntity> findByTermIdAndSchoolIdAndExamDate(
            @Param("termId") Long termId,
            @Param("schoolId") Long schoolId,
            @Param("examDate") LocalDate examDate);
}
