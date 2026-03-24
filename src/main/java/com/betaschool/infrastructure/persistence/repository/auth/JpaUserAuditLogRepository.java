package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.UserAuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JpaUserAuditLogRepository extends JpaRepository<UserAuditLogEntity, Long> {
    List<UserAuditLogEntity> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<UserAuditLogEntity> findBySchoolIdOrderByCreatedAtDesc(Long schoolId);
}
