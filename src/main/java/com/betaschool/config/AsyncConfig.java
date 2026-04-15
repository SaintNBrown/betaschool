package com.betaschool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables @Async and @Scheduled.
 *
 * Provides a dedicated thread pool for long-running bulk jobs (report card generation).
 * Bounded to 4 concurrent jobs max so a large school does not starve the connection pool.
 *
 * Pool sizing rationale:
 *   - Report card generation is mostly DB-bound (reads) and CPU-light (PDF layout).
 *   - Each job holds ~1–2 HikariCP connections at a time (reads are read-only txns).
 *   - With DB_POOL_SIZE=5, 4 job threads still leave 1 connection for HTTP requests.
 *   - Raise ASYNC_JOB_THREADS env var on dedicated instances with larger pool sizes.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Named executor used by @Async("jobExecutor") on BulkReportCardJobService.
     * Not the default executor — email sends still use Spring's default (SimpleAsyncTaskExecutor),
     * keeping fire-and-forget email separate from bounded job execution.
     */
    @Bean(name = "jobExecutor")
    public Executor jobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(Integer.parseInt(
                System.getenv().getOrDefault("ASYNC_JOB_THREADS", "4")));
        // Queue up to 20 jobs before rejecting new submissions
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("betaschool-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(120);
        executor.initialize();
        return executor;
    }
}
