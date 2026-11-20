package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TaskSchedulerRouter;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Sweepers must not run on the STOMP heartbeat scheduler (S15P21A604-982).
 *
 * <p>Reads the scheduler the {@code @Scheduled} registrar actually resolved rather than starting a
 * probe task — a probe bean would need its own configuration and split the context cache.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SchedulingConfigurationIntegrationTest {

    @Autowired private ScheduledAnnotationBeanPostProcessor scheduling;
    @Autowired @Qualifier("messageBrokerTaskScheduler") private TaskScheduler brokerScheduler;

    @Test
    void sweepersRunOnTheirOwnPoolAndNotOnTheBrokerHeartbeatScheduler() {
        ScheduledTaskRegistrar registrar =
                (ScheduledTaskRegistrar) ReflectionTestUtils.getField(scheduling, "registrar");
        TaskScheduler used = registrar.getScheduler();
        // Without a configured scheduler the registrar holds a router that picks one per task.
        if (used instanceof TaskSchedulerRouter router) {
            used = ReflectionTestUtils.invokeMethod(router, "determineDefaultScheduler");
        }

        assertNotSame(brokerScheduler, used, "스위퍼가 STOMP heartbeat 스케줄러를 같이 쓴다");
        ThreadPoolTaskScheduler pool = assertInstanceOf(ThreadPoolTaskScheduler.class, used);
        assertTrue(pool.getThreadNamePrefix().startsWith("sweeper-"), pool.getThreadNamePrefix());
    }
}
