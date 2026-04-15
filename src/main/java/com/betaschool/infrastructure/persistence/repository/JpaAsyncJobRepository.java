package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity;
import com.betaschool.infrastructure.persistence.entity.AsyncJobEntity.JobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface JpaAsyncJobRepository extends JpaRepository<AsyncJobEntity, UUID> {

    Optional<AsyncJobEntity> findByIdAndSchoolId(UUID id, Long schoolId);

    /**
     * Lists recent jobs for a school, optionally filtered by type and/or status.
     * Newest first. Used by the job listing endpoint.
     */
    @Query("""
        SELECT j FROM AsyncJobEntity j
        WHERE j.schoolId = :schoolId
          AND (:jobType IS NULL OR j.jobType = :jobType)
          AND (:status  IS NULL OR j.status  = :status)
        ORDER BY j.createdAt DESC
        """)
    List<AsyncJobEntity> findBySchoolIdAndFilters(
            @Param("schoolId") Long schoolId,
            @Param("jobType")  String jobType,
            @Param("status")   JobStatus status);
}
