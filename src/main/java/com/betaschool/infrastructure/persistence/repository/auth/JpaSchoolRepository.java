package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity;
import com.betaschool.infrastructure.persistence.entity.auth.SchoolEntity.SchoolStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaSchoolRepository extends JpaRepository<SchoolEntity, Long> {
    Optional<SchoolEntity> findBySlug(String slug);
    boolean existsBySlug(String slug);
    boolean existsByEmail(String email);
    List<SchoolEntity> findByStatus(SchoolStatus status);
    Page<SchoolEntity> findAll(Pageable pageable);

    @Query("SELECT s.status FROM SchoolEntity s WHERE s.id = :schoolId")
    Optional<SchoolStatus> findStatusById(@Param("schoolId") Long schoolId);
}
