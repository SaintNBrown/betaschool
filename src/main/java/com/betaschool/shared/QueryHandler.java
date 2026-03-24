package com.betaschool.shared;

/**
 * Every query handler implements this interface.
 * Query handlers must be @Transactional(readOnly = true).
 *
 * @param <Q> the Query type
 * @param <R> the return type
 */
public interface QueryHandler<Q extends Query<R>, R> {
    R handle(Q query);
}
