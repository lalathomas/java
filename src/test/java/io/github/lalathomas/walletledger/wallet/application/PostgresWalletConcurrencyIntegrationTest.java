package io.github.lalathomas.walletledger.wallet.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
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

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    @Test
    void postgresRowLockPreventsOverdraftAndSerializesDuplicateRequests() throws Exception {
        try (java.sql.Connection connection = jdbcTemplate.getDataSource().getConnection()) {
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
