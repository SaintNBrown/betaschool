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

    /**
     * Finds SUBJECT slots in OTHER class-sessions (not the one being published)
     * where the given teacher is already scheduled on the given day and the time
     * windows overlap.
     *
     * Overlap condition: existing.startTime < :endTime AND existing.endTime > :startTime
     * (standard interval overlap — two intervals overlap iff neither ends before the other starts)
     *
     * Only checks active timetables — historical versions do not block scheduling.
     * JOIN FETCHes the timetable → classSession → clazz chain and classSubject → subject
     * in a single query to avoid N+1 lazy-load hits when building conflict messages.
     */
    @Query("""
        SELECT s FROM TimetableSlotEntity s
        JOIN FETCH s.timetable tt
        JOIN FETCH tt.classSession tcs
        JOIN FETCH tcs.clazz cl
        JOIN FETCH s.classSubject csubj
        JOIN FETCH csubj.subject subj
        JOIN csubj.teacherAssignment ta
        JOIN ta.teacher t
        WHERE t.id = :teacherId
          AND s.dayOfWeek = :dayOfWeek
          AND s.startTime < :endTime
          AND s.endTime > :startTime
          AND tcs.id <> :excludeClassSessionId
          AND tt.active = true
          AND s.schoolId = :schoolId
        """)
    List<TimetableSlotEntity> findConflictingTeacherSlots(
            @Param("teacherId")              Long teacherId,
            @Param("dayOfWeek")              TimetableSlotEntity.DayOfWeek dayOfWeek,
            @Param("startTime")              java.time.LocalTime startTime,
            @Param("endTime")                java.time.LocalTime endTime,
            @Param("excludeClassSessionId")  Long excludeClassSessionId,
            @Param("schoolId")               Long schoolId);

    /**
     * All timetable slots for a school (active timetables only) with the chain
     * needed for timetables.csv: slot → timetable → classSession → clazz,
     * and classSubject → subject when slotType = SUBJECT.
     * Ordered for deterministic CSV output.
     */
    @Query("""        
        SELECT s FROM TimetableSlotEntity s
        JOIN FETCH s.timetable tt
        JOIN FETCH tt.classSession cs
        JOIN FETCH cs.clazz cl
        LEFT JOIN FETCH s.classSubject csubj
        LEFT JOIN FETCH csubj.subject subj
        WHERE s.schoolId = :schoolId
          AND tt.active = true
        ORDER BY cl.name, s.dayOfWeek, s.sortOrder, s.startTime
        """)
    List<TimetableSlotEntity> findAllActiveForExport(@Param("schoolId") Long schoolId);

    /**
     * Loads all active SUBJECT slots for a school (excluding a given classSession)
     * with the teacher chain pre-fetched. Used by the timetable generator to build
     * the "teacher occupied" map before running the constraint solver — one query
     * replaces what would otherwise be O(N×days×slots) calls.
     */
    @Query("""        
        SELECT s FROM TimetableSlotEntity s
        JOIN FETCH s.timetable tt
        JOIN FETCH tt.classSession tcs
        JOIN FETCH s.classSubject csubj
        JOIN FETCH csubj.teacherAssignment ta
        JOIN FETCH ta.teacher t
        WHERE s.schoolId = :schoolId
          AND tt.active = true
          AND s.slotType = 'SUBJECT'
          AND tcs.id <> :excludeClassSessionId
        """)
    List<TimetableSlotEntity> findAllActiveSubjectSlotsForSchoolExcludingClass(
            @Param("schoolId") Long schoolId,
            @Param("excludeClassSessionId") Long excludeClassSessionId);
}
