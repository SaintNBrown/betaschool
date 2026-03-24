package com.betaschool.query.model;

import com.betaschool.shared.Query;

public sealed interface SchoolConfigQuery {

    /** Fetch the current score config for the caller's school. */
    record GetSchoolScoreConfigQuery() implements Query<SchoolConfigQueryResult.SchoolScoreConfig>, SchoolConfigQuery {}
}
