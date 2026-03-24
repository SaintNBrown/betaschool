package com.betaschool.shared;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.GenericTypeResolver;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CommandBus resolves and dispatches commands to the correct handler at runtime.
 * Controllers use this instead of injecting handlers directly — decoupling API from logic.
 */
@Component
@RequiredArgsConstructor
public class CommandBus {

    private final ApplicationContext context;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R> R dispatch(Command<R> command) {
        Map<String, CommandHandler> handlers = context.getBeansOfType(CommandHandler.class);

        for (CommandHandler handler : handlers.values()) {
            Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(
                    handler.getClass(), CommandHandler.class);

            if (typeArgs != null && typeArgs[0].isAssignableFrom(command.getClass())) {
                return (R) handler.handle(command);
            }
        }

        throw new IllegalArgumentException(
                "No CommandHandler found for: " + command.getClass().getSimpleName());
    }
}
