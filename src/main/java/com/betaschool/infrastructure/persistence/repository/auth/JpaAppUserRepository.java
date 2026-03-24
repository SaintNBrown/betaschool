package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity;
import com.betaschool.infrastructure.persistence.entity.auth.AppUserEntity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaAppUserRepository extends JpaRepository<AppUserEntity, Long> {

    Optional<AppUserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    List<AppUserEntity> findBySchoolId(Long schoolId);

    List<AppUserEntity> findBySchoolIdAndRole(Long schoolId, UserRole role);

    @Query("SELECT u FROM AppUserEntity u LEFT JOIN FETCH u.school s WHERE u.email = :email")
    Optional<AppUserEntity> findByEmailWithSchool(@Param("email") String email);
}
