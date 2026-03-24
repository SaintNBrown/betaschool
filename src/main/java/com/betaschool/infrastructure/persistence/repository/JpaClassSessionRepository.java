package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.ClassSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaClassSessionRepository extends JpaRepository<ClassSessionEntity, Long> {

    Optional<ClassSessionEntity> findByClazzIdAndSessionIdAndSchoolId(Long classId, Long sessionId, Long schoolId);
    boolean existsByClazzIdAndSessionIdAndSchoolId(Long classId, Long sessionId, Long schoolId);
    Optional<ClassSessionEntity> findByIdAndSchoolId(Long id, Long schoolId);

    @Query("SELECT cs FROM ClassSessionEntity cs " +
           "JOIN FETCH cs.clazz " +
           "JOIN FETCH cs.session " +
           "WHERE cs.session.id = :sessionId AND cs.schoolId = :schoolId")
    List<ClassSessionEntity> findBySessionIdAndSchoolIdWithDetails(
            @Param("sessionId") Long sessionId,
            @Param("schoolId") Long schoolId);
}
