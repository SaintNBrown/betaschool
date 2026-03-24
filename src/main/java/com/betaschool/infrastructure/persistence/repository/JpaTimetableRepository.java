package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TimetableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTimetableRepository extends JpaRepository<TimetableEntity, Long> {

    Optional<TimetableEntity> findByIdAndSchoolId(Long id, Long schoolId);

    /** Returns the currently active timetable for a class-session. */
    Optional<TimetableEntity> findByClassSessionIdAndSchoolIdAndActiveTrue(
            Long classSessionId, Long schoolId);

    /** Returns all versions (active + historical) ordered newest first. */
    List<TimetableEntity> findByClassSessionIdAndSchoolIdOrderByVersionDesc(
            Long classSessionId, Long schoolId);

    /** Count of all versions for a class-session — used to enforce the 5-version cap. */
    int countByClassSessionIdAndSchoolId(Long classSessionId, Long schoolId);

    /** Deactivates all timetables for a class-session — called before activating a new one. */
    @Modifying
    @Query("UPDATE TimetableEntity t SET t.active = false " +
           "WHERE t.classSession.id = :classSessionId AND t.schoolId = :schoolId AND t.active = true")
    void deactivateAllForClassSession(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /** Returns the oldest timetable(s) — used when pruning beyond 5 versions. */
    @Query("SELECT t FROM TimetableEntity t " +
           "WHERE t.classSession.id = :classSessionId AND t.schoolId = :schoolId " +
           "ORDER BY t.version ASC")
    List<TimetableEntity> findByClassSessionIdAndSchoolIdOrderByVersionAsc(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /** Maximum version number for a class-session — used to set the next version. */
    @Query("SELECT COALESCE(MAX(t.version), 0) FROM TimetableEntity t " +
           "WHERE t.classSession.id = :classSessionId AND t.schoolId = :schoolId")
    int findMaxVersionByClassSessionIdAndSchoolId(
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);
}
