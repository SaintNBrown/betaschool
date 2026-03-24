package com.betaschool.query.model;

import com.betaschool.shared.Query;

import java.util.List;

public sealed interface TimetableQuery {

    /** Returns the currently active timetable for a class-session. */
    record GetActiveTimetableQuery(
            Long classSessionId
    ) implements Query<TimetableQueryResult.TimetableDetail>, TimetableQuery {}

    /** Returns all versions (active + historical) as summaries, newest first. */
    record GetTimetableHistoryQuery(
            Long classSessionId
    ) implements Query<List<TimetableQueryResult.TimetableSummary>>, TimetableQuery {}

    /** Returns a specific timetable version by its id. */
    record GetTimetableByIdQuery(
            Long timetableId
    ) implements Query<TimetableQueryResult.TimetableDetail>, TimetableQuery {}
}
