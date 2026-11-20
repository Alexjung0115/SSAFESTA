package com.example.ssafesta.common;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs a scheduled job on at most one instance at a time (S15P21A604-982).
 *
 * <p>Only for the jobs that have no row to claim. Most sweepers already split work with
 * {@code SKIP LOCKED} or a conditional {@code UPDATE}, and two instances running them is safe.
 * The ones listed in {@link Job} read a snapshot without locking and then act on it, so a second
 * instance repeats the work: a second reconciliation run and its ERROR log, a second storage
 * delete that then reports the row as changed underneath it.
 *
 * <p>A PostgreSQL session-level advisory lock, taken and released on one connection held for the
 * whole run — session locks belong to the connection, and a pooled connection handed back while
 * still holding one would keep it. The work itself uses its own connections. Two {@code int} keys
 * rather than {@code hashtext(name)}: a 32-bit hash of the name can collide, and a fixed
 * {@code (namespace, id)} pair cannot.
 *
 * <p>Not for request paths. A request that finds the lock taken would have to fail or skip, and
 * neither is acceptable where a user is waiting — {@code ProjectLogoCleanup.collectForBooth}
 * stays unlocked for that reason.
 */
@Component
public class ClusterSingleRun {

    private static final Logger log = LoggerFactory.getLogger(ClusterSingleRun.class);

    /** First key of every lock taken here — keeps them apart from any other advisory lock user. */
    static final int NAMESPACE = 0x5353_4653;

    /** Each id is fixed. Renumbering one lets an old and a new instance run the job together. */
    public enum Job {
        COIN_RECONCILIATION(1),
        AI_DOCUMENT_ORIGINAL_DELETE(2),
        PROJECT_LOGO_CLEANUP(3);

        private final int id;

        Job(int id) {
            this.id = id;
        }
    }

    private final JdbcTemplate jdbc;

    public ClusterSingleRun(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return {@code true} if the work ran here, {@code false} if another instance holds the job
     */
    public boolean runExclusively(Job job, Runnable work) {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
            if (!call(connection, "SELECT pg_try_advisory_lock(?, ?)", job)) {
                log.debug("다른 인스턴스가 실행 중이라 건너뜁니다 — job={}", job);
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                unlock(connection, job);
            }
        }));
    }

    /** Never throws — an exception here would replace the one the work may be propagating. */
    private void unlock(Connection connection, Job job) {
        try {
            if (!call(connection, "SELECT pg_advisory_unlock(?, ?)", job)) {
                log.warn("advisory lock 을 쥐고 있지 않았습니다 — job={}", job);
            }
        } catch (SQLException | RuntimeException failure) {
            log.warn("advisory lock 해제 실패 — job={} 원인={}", job, failure.toString());
        }
    }

    private static boolean call(Connection connection, String sql, Job job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, job.id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }
}
