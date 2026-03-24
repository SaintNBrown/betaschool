package com.betaschool.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables @Async for fire-and-forget tasks (email sends)
 * and @Scheduled for maintenance jobs (token cleanup).
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {}
