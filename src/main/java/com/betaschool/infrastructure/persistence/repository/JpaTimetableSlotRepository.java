package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.TimetableSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface JpaTimetableSlotRepository extends JpaRepository<TimetableSlotEntity, Long> {

    /** All slots for a timetable, ordered by day then sort_order then start_time. */
    @Query("SELECT s FROM TimetableSlotEntity s " +
           "WHERE s.timetable.id = :timetableId AND s.schoolId = :schoolId " +
           "ORDER BY s.dayOfWeek, s.sortOrder, s.startTime")
    List<TimetableSlotEntity> findByTimetableIdAndSchoolIdOrdered(
            @Param("timetableId") Long timetableId,
            @Param("schoolId") Long schoolId);

    /** Slots for a specific day within a timetable. */
    @Query("SELECT s FROM TimetableSlotEntity s " +
           "WHERE s.timetable.id = :timetableId AND s.schoolId = :schoolId " +
           "AND s.dayOfWeek = :dayOfWeek " +
           "ORDER BY s.sortOrder, s.startTime")
    List<TimetableSlotEntity> findByTimetableIdAndSchoolIdAndDay(
            @Param("timetableId") Long timetableId,
            @Param("schoolId") Long schoolId,
            @Param("dayOfWeek") TimetableSlotEntity.DayOfWeek dayOfWeek);
}
