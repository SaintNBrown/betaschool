package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaSessionRepository extends JpaRepository<SessionEntity, Long> {

    Optional<SessionEntity> findBySessionNameAndSchoolId(String sessionName, Long schoolId);
    boolean existsBySessionNameAndSchoolId(String sessionName, Long schoolId);
    List<SessionEntity> findBySchoolId(Long schoolId);
    Optional<SessionEntity> findByIdAndSchoolId(Long id, Long schoolId);

    /** Issue 5: find the designated current session for a school. */
    Optional<SessionEntity> findBySchoolIdAndCurrentTrue(Long schoolId);

    /** Issue 5: clear the current flag from all sessions of a school before setting a new one. */
    @Modifying
    @Query("UPDATE SessionEntity s SET s.current = false WHERE s.schoolId = :schoolId AND s.current = true")
    void clearCurrentSessionForSchool(@Param("schoolId") Long schoolId);
}
