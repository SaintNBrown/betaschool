package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.SchoolAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JpaSchoolAuditLogRepository extends JpaRepository<SchoolAuditLogEntity, Long> {
    List<SchoolAuditLogEntity> findBySchoolIdOrderByCreatedAtDesc(Long schoolId);
}
