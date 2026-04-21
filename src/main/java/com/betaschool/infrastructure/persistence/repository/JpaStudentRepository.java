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

    /**
     * Loads all students for a school whose emails appear in the given set.
     * Used by score import to resolve email → studentId in one query (no N+1).
     */
    @Query("SELECT s FROM StudentEntity s WHERE s.schoolId = :schoolId AND s.email IN :emails")
    List<StudentEntity> findBySchoolIdAndEmailIn(
            @Param("schoolId") Long schoolId,
            @Param("emails")   java.util.Collection<String> emails);

    /**
     * Returns all emails currently registered for a school.
     * Used during student import to detect duplicate emails against the DB in one round-trip.
     */
    @Query("SELECT s.email FROM StudentEntity s WHERE s.schoolId = :schoolId AND s.email IS NOT NULL")
    java.util.Set<String> findAllEmailsBySchoolId(@Param("schoolId") Long schoolId);
}
