package com.example.ssafesta.booth;

import static com.example.ssafesta.booth.BoothLayoutTestSupport.grantLease;
import static com.example.ssafesta.booth.BoothLayoutTestSupport.saveRequest;
import static com.example.ssafesta.booth.BoothTestSupport.createMemberWithWallet;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.ssafesta.CapturingStatementInspector;
import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import com.example.ssafesta.wallet.WalletService;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A room that is showing a layout is answered in one statement (S15P21A604-983).
 *
 * <p>Unity asks for twelve rooms on every world entry, and each answer used to cost five
 * statements. Response equality is {@code BoothLayoutApiIntegrationTest}'s golden body; this counts
 * what was sent to the database, filtered by table name so a background sweeper cannot move it
 * (T-154).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.ssafesta.CapturingStatementInspector")
class BoothRoomLayoutSingleStatementTest {

    private static final List<String> LOOKUP_TABLES =
            List.of("booth_slots", "booth_leases", "booths", "booth_layout_published_versions");

    @Autowired private BoothLayoutQueryService query;
    @Autowired private BoothLayoutService layouts;
    @Autowired private BoothRepository booths;
    @Autowired private UserRepository users;
    @Autowired private WalletService wallets;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void freeSlots() {
        BoothTestSupport.releaseAllSlots(jdbc);
    }

    @Test
    void aShowingRoomIsReadInOneStatement() {
        Long userId = createMemberWithWallet(users, wallets, "한문장방");
        Long boothId = booths.save(new Booth(userId, "한문장방 부스")).getId();
        Long slotId = grantLease(jdbc, boothId, userId);
        layouts.saveDraft(boothId, userId, saveRequest(0));
        layouts.publish(boothId, userId);

        CapturingStatementInspector.clear();
        query.findPublishedBySlot(slotId);

        List<String> lookups = CapturingStatementInspector.matching("").stream()
                .filter(sql -> LOOKUP_TABLES.stream().anyMatch(table -> mentions(sql, table)))
                .toList();
        assertEquals(1, lookups.size(), "게시 중인 방은 한 문장이어야 한다. 실행된 문장: " + lookups);
        String sql = lookups.getFirst();
        Stream.of("booth_leases", "booths", "booth_layout_published_versions").forEach(table ->
                assertEquals(true, mentions(sql, table), table + " 가 같은 문장에 없다: " + sql));
    }

    /** Word match — {@code booths} must not count {@code booth_slots}, and Hibernate writes {@code a,b}. */
    private static boolean mentions(String sql, String table) {
        return Pattern.compile("(^|[^a-z_])" + table + "([^a-z_]|$)").matcher(sql).find();
    }
}
