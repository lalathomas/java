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

    @Test
    void postgresRefundLockSerializesSameKeyAndDistinctKeyRaces() throws Exception {
        UUID sameKeyPlayerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                sameKeyPlayerId,
                new MoneyMovementCommand(
                        100,
                        "Initial balance",
                        "postgres-refund-initial-1",
                        "postgres-refund-credit-1"
                )
        );
        MoneyMovementResult sameKeyDebit = walletService.debit(
                sameKeyPlayerId,
                new MoneyMovementCommand(
                        30,
                        "Purchase",
                        "postgres-refund-debit-1",
                        "postgres-refund-debit-key-1"
                )
        );
        RefundCommand sameRefund = new RefundCommand(
                "Concurrent refund",
                "postgres-same-refund",
                "postgres-same-refund-key"
        );

        List<MoneyMovementResult> sameKeyResults = runConcurrently(
                12,
                ignored -> walletService.refund(
                        sameKeyPlayerId,
                        sameKeyDebit.transactionId(),
                        sameRefund
                )
        );
        assertThat(sameKeyResults)
                .extracting(MoneyMovementResult::transactionId)
                .containsOnly(sameKeyResults.get(0).transactionId());
        assertThat(sameKeyResults).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(sameKeyResults).filteredOn(MoneyMovementResult::replayed).hasSize(11);
        assertThat(walletService.getBalance(sameKeyPlayerId).balance()).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE refunded_debit_id = ?",
                Long.class,
                sameKeyDebit.transactionId()
        )).isEqualTo(1L);

        UUID distinctKeyPlayerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                distinctKeyPlayerId,
                new MoneyMovementCommand(
                        100,
                        "Initial balance",
                        "postgres-refund-initial-2",
                        "postgres-refund-credit-2"
                )
        );
        MoneyMovementResult distinctKeyDebit = walletService.debit(
                distinctKeyPlayerId,
                new MoneyMovementCommand(
                        25,
                        "Purchase",
                        "postgres-refund-debit-2",
                        "postgres-refund-debit-key-2"
                )
        );
        record RefundOutcome(boolean successful, WalletErrorCode errorCode) {
        }

        List<RefundOutcome> distinctKeyResults = runConcurrently(12, index -> {
            try {
                walletService.refund(
                        distinctKeyPlayerId,
                        distinctKeyDebit.transactionId(),
                        new RefundCommand(
                                "Concurrent refund",
                                "postgres-distinct-refund-" + index,
                                "postgres-distinct-refund-key-" + index
                        )
                );
                return new RefundOutcome(true, null);
            } catch (WalletException exception) {
                return new RefundOutcome(false, exception.getCode());
            }
        });

        assertThat(distinctKeyResults).filteredOn(RefundOutcome::successful).hasSize(1);
        assertThat(distinctKeyResults)
                .filteredOn(outcome -> !outcome.successful())
                .extracting(RefundOutcome::errorCode)
                .containsOnly(WalletErrorCode.DEBIT_ALREADY_REFUNDED)
                .hasSize(11);
        assertThat(walletService.getBalance(distinctKeyPlayerId).balance()).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE refunded_debit_id = ?",
                Long.class,
                distinctKeyDebit.transactionId()
        )).isEqualTo(1L);
        assertThat(walletService.reconcile(distinctKeyPlayerId).consistent()).isTrue();
    }

    @Test
    void postgresMigrationEnforcesOneLinkedCreditPerDebit() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                playerId,
                new MoneyMovementCommand(
                        20,
                        "Initial balance",
                        "postgres-constraint-initial",
                        "postgres-constraint-credit"
                )
        );
        MoneyMovementResult debit = walletService.debit(
                playerId,
                new MoneyMovementCommand(
                        10,
                        "Purchase",
                        "postgres-constraint-debit",
                        "postgres-constraint-debit-key"
                )
        );
        walletService.refund(
                playerId,
                debit.transactionId(),
                new RefundCommand(
                        "Refund",
                        "postgres-constraint-refund",
                        "postgres-constraint-refund-key"
                )
        );

        Long walletId = jdbcTemplate.queryForObject(
                "SELECT id FROM wallets WHERE player_id = ?",
                Long.class,
                playerId
        );
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO ledger_entries (
                    id, wallet_id, transaction_type, amount, balance_after,
                    reason, reference_id, idempotency_key, refunded_debit_id, created_at
                ) VALUES (?, ?, 'CREDIT', 10, 20, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                walletId,
                "Duplicate refund",
                "postgres-duplicate-refund",
                "postgres-duplicate-refund-key",
                debit.transactionId()
        )).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE refunded_debit_id = ?",
                Long.class,
                debit.transactionId()
        )).isEqualTo(1L);
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
