package com.betaschool.infrastructure.persistence.repository.auth;

import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity;
import com.betaschool.infrastructure.persistence.entity.auth.UserProfileEntity.ProfileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface JpaUserProfileRepository extends JpaRepository<UserProfileEntity, Long> {

    Optional<UserProfileEntity> findByUserId(Long userId);

    Optional<UserProfileEntity> findByProfileIdAndProfileType(Long profileId, ProfileType profileType);

    /**
     * Batch lookup: fetch all UserProfile + AppUser records for a set of teacher/student
     * profile IDs in a single query.
     *
     * Used by GetAllTeachersHandler to resolve account status for all teachers at once
     * instead of issuing one SELECT per teacher (which is O(N) queries for N teachers).
     */
    @Query("""
        SELECT up FROM UserProfileEntity up
        JOIN FETCH up.user u
        WHERE up.profileId IN :profileIds
          AND up.profileType = :profileType
        """)
    List<UserProfileEntity> findByProfileIdInAndProfileType(
            @Param("profileIds") Collection<Long> profileIds,
            @Param("profileType") ProfileType profileType);
}