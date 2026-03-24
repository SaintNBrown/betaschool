package com.betaschool.query.model;

import com.betaschool.shared.Query;
import java.util.List;

public sealed interface TeacherQuery {
    record GetTeacherByIdQuery(Long teacherId) implements Query<TeacherQueryResult.TeacherDetail>, TeacherQuery {}
    record GetAllTeachersQuery() implements Query<List<TeacherQueryResult.TeacherSummary>>, TeacherQuery {}
    record GetTeacherClassesQuery(Long teacherId, Long sessionId) implements Query<List<TeacherQueryResult.TeacherClassItem>>, TeacherQuery {}
    record GetTeacherSubjectsQuery(Long teacherId, Long sessionId) implements Query<List<TeacherQueryResult.TeacherSubjectItem>>, TeacherQuery {}
}
