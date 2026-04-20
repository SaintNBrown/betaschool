package com.betaschool.infrastructure.persistence.repository;

import com.betaschool.infrastructure.persistence.entity.ResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JpaResultRepository extends JpaRepository<ResultEntity, Long> {

    Optional<ResultEntity> findByExaminationIdAndStudentIdAndSchoolId(
            Long examinationId, Long studentId, Long schoolId);
    boolean existsByExaminationIdAndStudentIdAndSchoolId(
            Long examinationId, Long studentId, Long schoolId);
    Optional<ResultEntity> findByIdAndSchoolId(Long id, Long schoolId);

    /** All exam scores already recorded for an examination — used to pre-populate the result entry modal. */
    @Query("""
        SELECT r FROM ResultEntity r
        JOIN FETCH r.student s
        WHERE r.examination.id = :examinationId
          AND r.schoolId = :schoolId
        """)
    List<ResultEntity> findAllByExaminationIdAndSchoolId(
            @Param("examinationId") Long examinationId,
            @Param("schoolId") Long schoolId);

    @Query("""
        SELECT r FROM ResultEntity r
        JOIN FETCH r.examination e
        JOIN FETCH e.classSubject cs
        JOIN FETCH cs.subject sub
        JOIN FETCH r.student st
        WHERE st.id = :studentId
          AND e.term.id = :termId
          AND r.schoolId = :schoolId
        """)
    List<ResultEntity> findReportCard(
            @Param("studentId") Long studentId,
            @Param("termId") Long termId,
            @Param("schoolId") Long schoolId);

    @Query("""
        SELECT r FROM ResultEntity r
        JOIN FETCH r.examination e
        JOIN FETCH e.term t
        JOIN FETCH e.classSubject cs
        JOIN FETCH cs.subject sub
        WHERE r.student.id = :studentId
          AND t.session.id = :sessionId
          AND r.schoolId = :schoolId
        ORDER BY t.termNumber, sub.name
        """)
    List<ResultEntity> findByStudentIdAndSessionIdAndSchoolId(
            @Param("studentId") Long studentId,
            @Param("sessionId") Long sessionId,
            @Param("schoolId") Long schoolId);

    /**
     * Returns [studentId, combinedTotal] for every student in a class-session for a term.
     * combinedTotal = SUM(exam score) + SUM(test/CA score) per student.
     *
     * Two sub-selects are used because ResultEntity and TestScoreEntity are separate
     * tables with different rows per examination. A straight JOIN would multiply rows.
     *
     * Used to compute class positions on report cards.
     */
    @Query("""
        SELECT r.student.id,
               COALESCE(SUM(r.score), 0) +
               COALESCE((
                   SELECT SUM(ts.score)
                   FROM TestScoreEntity ts
                   JOIN ts.examination te
                   JOIN te.classSubject tcs
                   WHERE ts.student.id = r.student.id
                     AND tcs.classSession.id = :classSessionId
                     AND te.term.id = :termId
                     AND ts.schoolId = :schoolId
               ), 0)
        FROM ResultEntity r
        JOIN r.examination e
        JOIN e.classSubject cs
        WHERE e.term.id = :termId
          AND cs.classSession.id = :classSessionId
          AND r.schoolId = :schoolId
        GROUP BY r.student.id
        """)
    List<Object[]> findStudentTotalsForTermAndClassSession(
            @Param("termId") Long termId,
            @Param("classSessionId") Long classSessionId,
            @Param("schoolId") Long schoolId);

    /**
     * All results for a school with the full chain needed for exam_results.csv:
     * result → examination → term → classSession → clazz/session, classSubject → subject.
     * One query — no N+1. Used exclusively by the data export endpoint.
     */
    @Query("""        
        SELECT r FROM ResultEntity r
        JOIN FETCH r.student st
        JOIN FETCH r.examination e
        JOIN FETCH e.term t
        JOIN FETCH t.classSession cs
        JOIN FETCH cs.clazz cl
        JOIN FETCH cs.session sess
        JOIN FETCH e.classSubject csub
        JOIN FETCH csub.subject sub
        WHERE r.schoolId = :schoolId
        ORDER BY sess.sessionName, t.termNumber, cl.name, st.surname, st.otherNames
        """)
    List<ResultEntity> findAllForExport(@Param("schoolId") Long schoolId);
}
