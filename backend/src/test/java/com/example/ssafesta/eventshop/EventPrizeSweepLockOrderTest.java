package com.example.ssafesta.eventshop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.CapturingStatementInspector;
import com.example.ssafesta.TestcontainersConfiguration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The prize sweep reads its ids in a fixed order (S15P21A604-982).
 *
 * <p>The sweep locks each prize in turn inside one transaction, so two instances that walk the same
 * ids in different orders can deadlock. That is timing-dependent and a concurrency test would pass
 * by luck on an unordered query as often as not — this checks the one thing that removes the
 * window, the {@code order by} in the statements themselves.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.ssafesta.CapturingStatementInspector")
class EventPrizeSweepLockOrderTest {

    @Autowired private EventPrizeClosingService closing;

    @Test
    void bothSweepQueriesReadPrizeIdsInIdOrder() {
        CapturingStatementInspector.clear();

        closing.closeExpiredPrizes(Instant.now());
        closing.drawPendingRaffles(Instant.now());

        List<String> idReads = CapturingStatementInspector.matching("from event_prizes").stream()
                .filter(sql -> !sql.contains("for update") && !sql.contains("for no key update"))
                .toList();
        // At least ours; the scheduled sweep may add its own pass meanwhile — those run the same query.
        assertTrue(idReads.size() >= 2, "마감·추첨 id 조회가 잡히지 않았다: " + idReads);
        idReads.forEach(sql -> assertTrue(sql.matches(".*order by \\w+\\.id\\s*$"),
                "id 순으로 읽지 않는다 — 두 인스턴스가 서로 다른 순서로 잠글 수 있다: " + sql));
    }
}
