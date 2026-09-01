package io.github.lalathomas.walletledger.wallet.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres-test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresWalletConcurrencyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.6-alpine")
    );

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    @Test
    void postgresRowLockPreventsOverdraftAndSerializesDuplicateRequests() throws Exception {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        UUID debitPlayerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                debitPlayerId,
                new MoneyMovementCommand(100, "Initial balance", "initial", "initial-credit")
        );

        List<DebitOutcome> debitOutcomes = runConcurrently(20, index -> {
            try {
                walletService.debit(
                        debitPlayerId,
                        new MoneyMovementCommand(
                                10,
                                "Concurrent purchase",
                                "purchase-" + index,
                                "postgres-debit-" + index
                        )
                );
                return DebitOutcome.success();
            } catch (WalletException exception) {
                return DebitOutcome.failure(exception.getCode());
            }
        });

        assertThat(debitOutcomes).filteredOn(DebitOutcome::successful).hasSize(10);
        assertThat(debitOutcomes)
                .filteredOn(outcome -> !outcome.successful())
                .extracting(DebitOutcome::errorCode)
                .containsOnly(WalletErrorCode.INSUFFICIENT_FUNDS)
                .hasSize(10);
        assertThat(walletService.getBalance(debitPlayerId).balance()).isZero();

        UUID duplicatePlayerId = walletService.createWallet(UUID.randomUUID()).playerId();
        MoneyMovementCommand duplicateRequest = new MoneyMovementCommand(
                9,
                "Single reward",
                "single-reward",
                "postgres-duplicate-key"
        );
        List<MoneyMovementResult> duplicateResults = runConcurrently(
                12,
                ignored -> walletService.credit(duplicatePlayerId, duplicateRequest)
        );

        assertThat(duplicateResults)
                .extracting(MoneyMovementResult::transactionId)
                .containsOnly(duplicateResults.get(0).transactionId());
        assertThat(duplicateResults).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(walletService.getBalance(duplicatePlayerId).balance()).isEqualTo(9);
        assertThat(walletService.getHistory(duplicatePlayerId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void lockTimeoutReturnsRetryableErrorAndSameKeyCanSucceed() throws Exception {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        Long walletId = jdbcTemplate.queryForObject(
                "SELECT id FROM wallets WHERE player_id = ?",
                Long.class,
                playerId
        );
        String requestBody = """
                {
                  "amount": 5,
                  "reason": "Lock timeout test",
                  "referenceId": "lock-timeout-1"
                }
                """;

        try (Connection locker = jdbcTemplate.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            try (PreparedStatement statement = locker.prepareStatement(
                    "SELECT id FROM wallets WHERE id = ? FOR UPDATE"
            )) {
                statement.setLong(1, walletId);
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }

            mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                            .header("Idempotency-Key", "lock-timeout-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("WALLET_BUSY"));
            locker.rollback();
        }

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header("Idempotency-Key", "lock-timeout-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(5))
                .andExpect(jsonPath("$.replayed").value(false));
        assertThat(walletService.getHistory(playerId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void concurrentPostgresRefundAttemptsApplyExactlyOnce() throws Exception {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                playerId,
                new MoneyMovementCommand(100, "Initial balance", "refund-initial", "refund-credit")
        );
        MoneyMovementResult debit = walletService.debit(
                playerId,
                new MoneyMovementCommand(30, "Shop purchase", "refund-purchase", "refund-debit")
        );
        int requestCount = 12;

        List<String> outcomes = runConcurrently(requestCount, index -> {
            try {
                walletService.refund(
                        playerId,
                        debit.transactionId(),
                        new RefundCommand(
                                "Concurrent cancellation",
                                "postgres-ticket-" + index,
                                "postgres-refund-" + index
                        )
                );
                return "REFUNDED";
            } catch (WalletException exception) {
                return exception.getCode().name();
            }
        });

        assertThat(outcomes).filteredOn("REFUNDED"::equals).hasSize(1);
        assertThat(outcomes)
                .filteredOn(WalletErrorCode.TRANSACTION_ALREADY_REFUNDED.name()::equals)
                .hasSize(requestCount - 1);
        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(100);
        assertThat(walletService.getHistory(playerId, 0, 20).transactions())
                .filteredOn(entry -> entry.reversalOfTransactionId() != null)
                .hasSize(1);

        UUID duplicatePlayerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                duplicatePlayerId,
                new MoneyMovementCommand(
                        80,
                        "Initial balance",
                        "duplicate-refund-initial",
                        "duplicate-refund-credit"
                )
        );
        MoneyMovementResult duplicateDebit = walletService.debit(
                duplicatePlayerId,
                new MoneyMovementCommand(
                        25,
                        "Shop purchase",
                        "duplicate-refund-purchase",
                        "duplicate-refund-debit"
                )
        );
        RefundCommand duplicateRefund = new RefundCommand(
                "Purchase cancelled",
                "duplicate-refund-ticket",
                "one-shared-refund-key"
        );

        List<MoneyMovementResult> duplicateResults = runConcurrently(
                requestCount,
                ignored -> walletService.refund(
                        duplicatePlayerId,
                        duplicateDebit.transactionId(),
                        duplicateRefund
                )
        );

        assertThat(duplicateResults)
                .extracting(MoneyMovementResult::transactionId)
                .containsOnly(duplicateResults.get(0).transactionId());
        assertThat(duplicateResults).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(duplicateResults)
                .filteredOn(MoneyMovementResult::replayed)
                .hasSize(requestCount - 1);
        assertThat(walletService.getBalance(duplicatePlayerId).balance()).isEqualTo(80);
        assertThat(walletService.getHistory(duplicatePlayerId, 0, 20).totalElements()).isEqualTo(3);
    }

    private static <T> List<T> runConcurrently(
            int taskCount,
            IndexedOperation<T> operation
    ) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(taskCount);
        CountDownLatch ready = new CountDownLatch(taskCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Callable<T>> tasks = java.util.stream.IntStream.range(0, taskCount)
                    .mapToObj(index -> (Callable<T>) () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting to start concurrent test");
                        }
                        return operation.execute(index);
                    })
                    .toList();
            List<Future<T>> futures = tasks.stream().map(executor::submit).toList();

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return futures.stream().map(PostgresWalletConcurrencyIntegrationTest::getFuture).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static <T> T getFuture(Future<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent PostgreSQL operation failed", exception);
        }
    }

    @FunctionalInterface
    private interface IndexedOperation<T> {
        T execute(int index) throws Exception;
    }

    private record DebitOutcome(boolean successful, WalletErrorCode errorCode) {
        static DebitOutcome success() {
            return new DebitOutcome(true, null);
        }

        static DebitOutcome failure(WalletErrorCode errorCode) {
            return new DebitOutcome(false, errorCode);
        }
    }
}
