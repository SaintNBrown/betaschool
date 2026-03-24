package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.SchoolScoreConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JpaSchoolScoreConfigRepository extends JpaRepository<SchoolScoreConfigEntity, Long> {

    Optional<SchoolScoreConfigEntity> findBySchoolId(Long schoolId);

    boolean existsBySchoolId(Long schoolId);
}
