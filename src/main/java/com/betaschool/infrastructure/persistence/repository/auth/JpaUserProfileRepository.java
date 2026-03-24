package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity.ProfileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface JpaUserProfileRepository extends JpaRepository<UserProfileEntity, Long> {
    Optional<UserProfileEntity> findByUserId(Long userId);
    Optional<UserProfileEntity> findByProfileIdAndProfileType(Long profileId, ProfileType profileType);
}
