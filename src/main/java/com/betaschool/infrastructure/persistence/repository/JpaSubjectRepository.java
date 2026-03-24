package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.SubjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaSubjectRepository extends JpaRepository<SubjectEntity, Long> {
    Optional<SubjectEntity> findByNameAndSchoolId(String name, Long schoolId);
    boolean existsByNameAndSchoolId(String name, Long schoolId);
    List<SubjectEntity> findBySchoolId(Long schoolId);
    Optional<SubjectEntity> findByIdAndSchoolId(Long id, Long schoolId);
}
