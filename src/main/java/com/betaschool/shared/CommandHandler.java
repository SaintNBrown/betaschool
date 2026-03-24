package com.betaschool.shared;

/**
 * Every command handler implements this interface.
 * Strict CQRS: handlers only handle one command type.
 *
 * @param <C> the Command type
 * @param <R> the return type
 */
public interface CommandHandler<C extends Command<R>, R> {
    R handle(C command);
}
