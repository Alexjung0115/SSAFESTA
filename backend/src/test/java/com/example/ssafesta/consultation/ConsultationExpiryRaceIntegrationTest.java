package com.example.ssafesta.consultation;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.booth.Booth;
import com.example.ssafesta.booth.BoothRepository;
import com.example.ssafesta.booth.BoothTestSupport;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 스위퍼는 잠근 행만 옮기고 그 행에만 알린다 (S15P21A604-982).
 *
 * <p>{@code @MockitoBean SimpMessagingTemplate messaging} 은 {@code ConsultationEventFailureIntegrationTest}
 * 와 같은 이름·타입이라 그 컨텍스트를 같이 쓴다 — 새 캐시 키를 만들지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConsultationExpiryRaceIntegrationTest {

    @Autowired private ConsultationService consultations;
    @Autowired private ConsultationRepository repository;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;
    @MockitoBean private SimpMessagingTemplate messaging;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    /**
     * 다른 인스턴스가 잡고 있는 행은 건너뛰고 알리지도 않는다.
     *
     * <p>옛 코드는 잠그지 않고 읽은 목록에 알린 뒤 벌크 갱신이 그 행의 잠금을 기다렸다 — 이 테스트에서는
     * 5초 안에 끝나지 못해 붉어진다.
     */
    @Test
    void aRowAnotherPassHoldsIsSkippedAndNotAnnounced() throws Exception {
        Overdue request = overdueRequest("잠금건너뜀");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> otherPass = pool.submit(() -> transactions.executeWithoutResult(status -> {
                repository.lockOverdue(Instant.now().minusSeconds(600));
                held.countDown();
                await(release);
            }));
            assertEquals(true, held.await(10, TimeUnit.SECONDS));

            Future<Integer> thisPass = pool.submit(consultations::expireStale);
            assertEquals(0, thisPass.get(5, TimeUnit.SECONDS));

            verify(messaging, never()).convertAndSendToUser(eq(String.valueOf(request.visitorId())),
                    anyString(), argThat(event -> isExpiredFor(event, request.requestId())));
            assertEquals("REQUESTED", statusOf(request.requestId()));

            release.countDown();
            otherPass.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 잡는 쪽이 없으면 그대로 만료되고 알림은 한 번이다. */
    @Test
    void anUnheldOverdueRequestIsExpiredAndAnnouncedOnce() {
        Overdue request = overdueRequest("정상만료");

        consultations.expireStale();

        assertEquals("EXPIRED", statusOf(request.requestId()));
        verify(messaging).convertAndSendToUser(eq(String.valueOf(request.visitorId())),
                anyString(), argThat(event -> isExpiredFor(event, request.requestId())));
    }

    /**
     * 취소는 대기 중일 때만 쓴다. 이미 만료된 행을 덮지 않는다.
     *
     * <p>옛 취소는 엔티티를 읽고 flush 해 {@code WHERE id = ?} 로 썼다. 읽은 뒤 만료가 커밋되면
     * 그것을 {@code CANCELLED} 로 덮었다 — 조건을 빼는 변이에서 이 테스트가 붉어진다.
     */
    @Test
    void cancellingWritesOnlyWhileTheRequestIsStillWaiting() {
        Overdue request = overdueRequest("만료후취소");
        consultations.expireStale();

        int cancelled = transactions.execute(status ->
                repository.cancelIfRequested(request.requestId(), Instant.now()));

        assertEquals(0, cancelled);
        assertEquals("EXPIRED", statusOf(request.requestId()));
    }

    private Overdue overdueRequest(String prefix) {
        Long ownerId = createMemberWithWallet(users, wallets, prefix);
        Long boothId = booths.save(new Booth(ownerId, prefix + " 부스")).getId();
        grantLease(jdbc, boothId, ownerId);
        Long visitorId = createMemberWithWallet(users, wallets, prefix + "방문");
        Long requestId = Long.valueOf(consultations.request(visitorId,
                new ConsultationService.RequestCommand(boothId, null, null)).requestId());
        jdbc.update("UPDATE consultations SET requested_at = now() - interval '11 minutes' WHERE id = ?",
                requestId);
        return new Overdue(requestId, visitorId);
    }

    private static boolean isExpiredFor(Object event, Long requestId) {
        return event instanceof Map<?, ?> map
                && "expired".equals(map.get("type"))
                && String.valueOf(requestId).equals(map.get("requestId"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private String statusOf(Long requestId) {
        return jdbc.queryForObject("SELECT status FROM consultations WHERE id = ?", String.class, requestId);
    }

    private record Overdue(Long requestId, Long visitorId) { }
}
