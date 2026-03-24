package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.StudentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaStudentRepository extends JpaRepository<StudentEntity, Long> {
    Optional<StudentEntity> findByEmailAndSchoolId(String email, Long schoolId);
    boolean existsByEmailAndSchoolId(String email, Long schoolId);
    Optional<StudentEntity> findByIdAndSchoolId(Long id, Long schoolId);
    List<StudentEntity> findBySchoolId(Long schoolId);

    @Query("SELECT s FROM StudentEntity s WHERE s.schoolId = :schoolId AND (" +
           "LOWER(s.surname) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           "LOWER(s.otherNames) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<StudentEntity> searchByNameAndSchoolId(
            @Param("search") String search,
            @Param("schoolId") Long schoolId,
            Pageable pageable);
}
