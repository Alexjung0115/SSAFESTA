package com.example.ssafesta.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Gives {@code @Scheduled} sweepers a pool of their own (S15P21A604-982).
 *
 * <p>Without this the sweepers ran on the STOMP broker's heartbeat scheduler. Spring resolves the
 * default {@code @Scheduled} scheduler as "the only {@code TaskScheduler} bean", and
 * {@code messageBrokerTaskScheduler} was that bean — Boot's own scheduler backs off as soon as any
 * {@code TaskScheduler} exists. A sweeper blocked on object storage or FastAPI then held a thread
 * the 25-second heartbeat needs, and a late heartbeat is what lets Cloudflare drop an idle socket
 * (S15P21A604-825).
 *
 * <p>The scheduler is a bean so the context closes it; built inside {@link #configureTasks} it would
 * outlive every test context that created one.
 */
@Configuration
class SchedulingConfiguration implements SchedulingConfigurer {

    private final ThreadPoolTaskScheduler sweeperTaskScheduler;

    SchedulingConfiguration(ThreadPoolTaskScheduler sweeperTaskScheduler) {
        this.sweeperTaskScheduler = sweeperTaskScheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(sweeperTaskScheduler);
    }

    @Configuration
    static class SweeperPool {

        @Bean
        ThreadPoolTaskScheduler sweeperTaskScheduler(
                @Value("${app.scheduling.pool-size:4}") int poolSize) {
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(poolSize);
            scheduler.setThreadNamePrefix("sweeper-");
            return scheduler;
        }
    }
}
