package com.betaschool.shared;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationContext;
import org.springframework.core.GenericTypeResolver;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * QueryBus resolves and dispatches queries to the correct handler at runtime.
 *
 * Also handles stale Redis cache entries: if the cache returns a LinkedHashMap
 * (plain JSON with no type info, from a previous serializer format) instead of
 * the expected domain object, the ClassCastException is caught here, the bad
 * cache entry is evicted, and the query is retried against the database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QueryBus {

    private final ApplicationContext context;
    private final CacheManager cacheManager;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R> R dispatch(Query<R> query) {
        Map<String, QueryHandler> handlers = context.getBeansOfType(QueryHandler.class);

        for (QueryHandler handler : handlers.values()) {
            Class<?>[] typeArgs = GenericTypeResolver.resolveTypeArguments(
                    handler.getClass(), QueryHandler.class);

            if (typeArgs != null && typeArgs[0].isAssignableFrom(query.getClass())) {
                try {
                    return (R) handler.handle(query);
                } catch (ClassCastException e) {
                    // Stale cache entry in wrong format (e.g. LinkedHashMap instead of ReportCard).
                    // Evict the bad key from all caches and retry once against the database.
                    log.warn("ClassCastException reading from cache for query={} — evicting stale entry and retrying. Cause: {}",
                            query.getClass().getSimpleName(), e.getMessage());
                    evictCachesForHandler(handler);
                    return (R) handler.handle(query);
                }
            }
        }

        throw new IllegalArgumentException(
                "No QueryHandler found for: " + query.getClass().getSimpleName());
    }

    /**
     * Evicts all cache entries for caches declared on the handler's handle() method.
     * This finds @Cacheable annotations and clears those specific caches.
     */
    private void evictCachesForHandler(QueryHandler<?, ?> handler) {
        try {
            for (Method method : handler.getClass().getMethods()) {
                Cacheable cacheable = method.getAnnotation(Cacheable.class);
                if (cacheable != null) {
                    for (String cacheName : cacheable.value()) {
                        Cache cache = cacheManager.getCache(cacheName);
                        if (cache != null) {
                            cache.clear();
                            log.info("Evicted stale cache: {}", cacheName);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to evict caches after ClassCastException: {}", ex.getMessage());
        }
    }
}
