package com.example.ssafesta.wallet;

import static com.example.ssafesta.wallet.WalletTestSupport.createMember;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ssafesta.CapturingStatementInspector;
import com.example.ssafesta.TestcontainersConfiguration;
import com.example.ssafesta.user.UserRepository;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A reconciliation run never loads the wallets (S15P21A604-983).
 *
 * <p>{@code CoinReconciliationIntegrationTest} proves the answers are right; this proves how they
 * are reached. It used to be {@code wallets.findAll()} plus every per-wallet total in memory — the
 * answers were the same, so only the statements show a regression back to that.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.example.ssafesta.CapturingStatementInspector")
class CoinReconciliationStatementTest {

    private static final Pattern WALLET_TABLES =
            Pattern.compile("(^|[^a-z_])(wallets|coin_ledger_entries)([^a-z_]|$)");

    @Autowired private CoinReconciliationService reconciliation;
    @Autowired private WalletService wallets;
    @Autowired private UserRepository users;

    @Test
    void aRunIsOneCountAndOneMismatchQuery() {
        wallets.openWallet(createMember(users, "정합문장"));

        CapturingStatementInspector.clear();
        CoinReconciliationRun run = reconciliation.run();

        assertEquals(CoinReconciliationRun.Status.COMPLETED, run.getStatus());
        List<String> walletReads = CapturingStatementInspector.matching("").stream()
                .filter(sql -> WALLET_TABLES.matcher(sql).find())
                .toList();
        assertEquals(2, walletReads.size(), "지갑·원장을 읽는 문장은 count 와 불일치 조회 둘이어야 한다: " + walletReads);
        assertTrue(walletReads.stream().anyMatch(sql -> sql.contains("count(")), walletReads.toString());
        assertTrue(walletReads.stream().anyMatch(sql -> sql.contains("<>")), walletReads.toString());
    }
}
