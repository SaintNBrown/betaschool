package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.AsyncJobFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JpaAsyncJobFileRepository extends JpaRepository<AsyncJobFileEntity, Long> {

    List<AsyncJobFileEntity> findByJobId(UUID jobId);

    long countByJobId(UUID jobId);
}
