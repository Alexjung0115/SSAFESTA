package com.example.ssafesta.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.common.ClusterSingleRun.Job;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One instance at a time, and the lock never outlives the run (S15P21A604-982).
 *
 * <p>Uses {@link Job#COIN_RECONCILIATION}: its schedule is pushed to 24 hours in tests, so the real
 * sweeper cannot take the lock underneath these cases the way the one-minute logo sweep could.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ClusterSingleRunIntegrationTest {

    private static final Job JOB = Job.COIN_RECONCILIATION;

    @Autowired private ClusterSingleRun singleRun;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void aSecondCallerSkipsWhileTheFirstHoldsTheJobAndRunsOnceItIsReleased() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = pool.submit(() -> singleRun.runExclusively(JOB, () -> {
                running.countDown();
                await(finish);
            }));
            assertTrue(running.await(10, TimeUnit.SECONDS));

            AtomicBoolean ranHere = new AtomicBoolean();
            assertFalse(singleRun.runExclusively(JOB, () -> ranHere.set(true)));
            assertFalse(ranHere.get(), "잡힌 동안 두 번째 호출이 본문을 실행했다");

            finish.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS));
            assertEquals(0, heldLocks(), "실행이 끝났는데 락이 남았다 — unlock 이 빠졌다");
            assertTrue(singleRun.runExclusively(JOB, () -> ranHere.set(true)));
            assertTrue(ranHere.get());
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aFailingRunPropagatesItsOwnExceptionAndStillReleasesTheJob() {
        IllegalStateException failure = new IllegalStateException("본문 실패");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> singleRun.runExclusively(JOB, () -> { throw failure; }));

        assertSame(failure, thrown);
        assertEquals(0, heldLocks(), "실패한 실행이 락을 쥔 채 남았다");
    }

    /**
     * Read from {@code pg_locks}, not by taking the lock again: session advisory locks are
     * re-entrant, so a pooled connection that leaked one would still "succeed" on the next call.
     */
    private int heldLocks() {
        Integer held = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory'"
                + " AND classid = ? AND objid = ? AND granted", Integer.class,
                ClusterSingleRun.NAMESPACE, 1);
        return held == null ? 0 : held;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
