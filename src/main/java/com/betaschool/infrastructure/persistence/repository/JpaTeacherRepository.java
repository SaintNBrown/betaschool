package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TeacherEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaTeacherRepository extends JpaRepository<TeacherEntity, Long> {
    Optional<TeacherEntity> findByEmailAndSchoolId(String email, Long schoolId);
    boolean existsByEmailAndSchoolId(String email, Long schoolId);
    Optional<TeacherEntity> findByIdAndSchoolId(Long id, Long schoolId);
    List<TeacherEntity> findBySchoolId(Long schoolId);
}
