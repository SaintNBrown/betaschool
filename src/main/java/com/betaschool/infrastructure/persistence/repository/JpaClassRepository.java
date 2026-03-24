package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.ClassEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaClassRepository extends JpaRepository<ClassEntity, Long> {
    Optional<ClassEntity> findByNameAndSchoolId(String name, Long schoolId);
    boolean existsByNameAndSchoolId(String name, Long schoolId);
    List<ClassEntity> findBySchoolId(Long schoolId);
    Optional<ClassEntity> findByIdAndSchoolId(Long id, Long schoolId);
}
