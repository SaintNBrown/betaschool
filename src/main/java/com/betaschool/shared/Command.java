package com.betaschool.shared;

/**
 * Marker interface for all Commands.
 * Commands mutate state and return a result of type R.
 * They must never be used for reads.
 */
public interface Command<R> {
}
