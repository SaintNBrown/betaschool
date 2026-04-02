package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TermEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTermRepository extends JpaRepository<TermEntity, Long> {

    Optional<TermEntity> findByClassSessionIdAndTermNumberAndSchoolId(
            Long classSessionId, Integer termNumber, Long schoolId);

    boolean existsByClassSessionIdAndTermNumberAndSchoolId(
            Long classSessionId, Integer termNumber, Long schoolId);

    Optional<TermEntity> findByIdAndSchoolId(Long id, Long schoolId);

    /**
     * Fetches terms with classSession → clazz and session eagerly to avoid N+1
     * when GetTermsByClassSessionHandler maps t.getClassSession().getClazz().getName()
     * and t.getSession().getSessionName() in a stream.
     */
    @Query("""
        SELECT t FROM TermEntity t
        JOIN FETCH t.classSession cs
        JOIN FETCH cs.clazz cl
        JOIN FETCH t.session s
        WHERE t.classSession.id = :classSessionId
          AND t.schoolId = :schoolId
        ORDER BY t.termNumber
        """)
    List<TermEntity> findByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /**
     * Loads the term with its classSession → clazz → session chain in one query.
     * Used by the report card and score handlers that need all names from a single term.
     */
    @Query("""
        SELECT t FROM TermEntity t
        JOIN FETCH t.classSession cs
        JOIN FETCH cs.clazz cl
        JOIN FETCH cs.session s
        WHERE t.id = :id
          AND t.schoolId = :schoolId
        """)
    Optional<TermEntity> findByIdAndSchoolIdWithDetails(
            @Param("id") Long id,
            @Param("schoolId") Long schoolId);
}
