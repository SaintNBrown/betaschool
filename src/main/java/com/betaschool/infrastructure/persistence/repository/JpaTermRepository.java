package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TermEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTermRepository extends JpaRepository<TermEntity, Long> {
    Optional<TermEntity> findByClassSessionIdAndTermNumberAndSchoolId(
            Long classSessionId, Integer termNumber, Long schoolId);
    boolean existsByClassSessionIdAndTermNumberAndSchoolId(
            Long classSessionId, Integer termNumber, Long schoolId);
    List<TermEntity> findByClassSessionIdAndSchoolId(Long classSessionId, Long schoolId);
    Optional<TermEntity> findByIdAndSchoolId(Long id, Long schoolId);
}
