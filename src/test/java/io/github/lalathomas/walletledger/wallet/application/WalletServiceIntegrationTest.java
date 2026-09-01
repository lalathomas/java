package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest
@ActiveProfiles("test")
class WalletServiceIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM fund_reservations");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    @Test
    void createsWalletAndRecordsCreditsAndDebits() {
        UUID playerId = UUID.randomUUID();

        WalletSnapshot created = walletService.createWallet(playerId);
        MoneyMovementResult credit = walletService.credit(
                playerId,
                command(100, "Mission reward", "mission-42", "credit-1")
        );
        MoneyMovementResult debit = walletService.debit(
                playerId,
                command(35, "Shop purchase", "purchase-91", "debit-1")
        );

        assertThat(created.balance()).isZero();
        assertThat(credit.type()).isEqualTo(TransactionType.CREDIT);
        assertThat(credit.balanceAfter()).isEqualTo(100);
        assertThat(credit.replayed()).isFalse();
        assertThat(debit.type()).isEqualTo(TransactionType.DEBIT);
        assertThat(debit.balanceAfter()).isEqualTo(65);
        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(65);

        TransactionHistory history = walletService.getHistory(playerId, 0, 20);
        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.transactions())
                .extracting(LedgerEntryView::transactionId)
                .containsExactly(debit.transactionId(), credit.transactionId());
        assertThat(history.transactions().get(0).reason()).isEqualTo("Shop purchase");
        assertThat(history.transactions().get(0).referenceId()).isEqualTo("purchase-91");
    }

    @Test
    void insufficientDebitRollsBackWithoutCreatingPartialLedgerEntry() {
        UUID playerId = createWalletWithBalance(20);

        assertWalletError(
                WalletErrorCode.INSUFFICIENT_FUNDS,
                () -> walletService.debit(
                        playerId,
                        command(25, "Too expensive", "purchase-1", "debit-too-large")
                )
        );

        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(20);
        TransactionHistory history = walletService.getHistory(playerId, 0, 20);
        assertThat(history.totalElements()).isEqualTo(1);
        assertThat(history.transactions()).allMatch(entry -> entry.type() == TransactionType.CREDIT);
    }

    @Test
    void repeatedRequestReturnsOriginalResultAndAppliesOnlyOnce() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        MoneyMovementCommand request = command(
                50,
                "Admin adjustment",
                "ticket-123",
                "same-request-key"
        );

        MoneyMovementResult first = walletService.credit(playerId, request);
        MoneyMovementResult replay = walletService.credit(playerId, request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
        assertThat(replay.balanceAfter()).isEqualTo(first.balanceAfter());
        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(50);
        assertThat(walletService.getHistory(playerId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyWithDifferentPayloadIsRejected() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                playerId,
                command(50, "Reward", "reward-1", "shared-key")
        );

        assertWalletError(
                WalletErrorCode.IDEMPOTENCY_CONFLICT,
                () -> walletService.credit(
                        playerId,
                        command(51, "Reward", "reward-1", "shared-key")
                )
        );
        assertWalletError(
                WalletErrorCode.IDEMPOTENCY_CONFLICT,
                () -> walletService.debit(
                        playerId,
                        command(50, "Reward", "reward-1", "shared-key")
                )
        );

        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(50);
        assertThat(walletService.getHistory(playerId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidInputMissingWalletAndDuplicateWallet() {
        UUID playerId = UUID.randomUUID();
        walletService.createWallet(playerId);

        assertWalletError(
                WalletErrorCode.WALLET_ALREADY_EXISTS,
                () -> walletService.createWallet(playerId)
        );
        assertWalletError(
                WalletErrorCode.WALLET_NOT_FOUND,
                () -> walletService.getBalance(UUID.randomUUID())
        );
        assertWalletError(
                WalletErrorCode.INVALID_AMOUNT,
                () -> walletService.credit(
                        playerId,
                        command(0, "Reward", "reward-1", "invalid-amount")
                )
        );
        assertWalletError(
                WalletErrorCode.INVALID_REQUEST,
                () -> walletService.credit(
                        playerId,
                        command(1, "   ", "reward-1", "blank-reason")
                )
        );
        assertWalletError(
                WalletErrorCode.INVALID_REQUEST,
                () -> walletService.credit(
                        playerId,
                        command(1, "Reward", "reward-1", "invalid key with spaces")
                )
        );

        assertThat(walletService.getBalance(playerId).balance()).isZero();
        assertThat(walletService.getHistory(playerId, 0, 20).totalElements()).isZero();
    }

    @Test
    void rejectsBalanceOverflowWithoutWritingLedgerEntry() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                playerId,
                command(Long.MAX_VALUE, "Maximum test credit", "max-1", "max-credit")
        );

        assertWalletError(
                WalletErrorCode.BALANCE_OVERFLOW,
                () -> walletService.credit(
                        playerId,
                        command(1, "Overflow", "overflow-1", "overflow-credit")
                )
        );

        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(Long.MAX_VALUE);
        assertThat(walletService.getHistory(playerId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void returnsDeterministicPaginatedHistory() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        for (int index = 1; index <= 5; index++) {
            walletService.credit(
                    playerId,
                    command(index, "Reward " + index, "reward-" + index, "page-key-" + index)
            );
        }

        TransactionHistory firstPage = walletService.getHistory(playerId, 0, 2);
        TransactionHistory secondPage = walletService.getHistory(playerId, 1, 2);
        TransactionHistory lastPage = walletService.getHistory(playerId, 2, 2);

        assertThat(firstPage.transactions()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(5);
        assertThat(firstPage.totalPages()).isEqualTo(3);
        assertThat(firstPage.first()).isTrue();
        assertThat(firstPage.last()).isFalse();
        assertThat(lastPage.transactions()).hasSize(1);
        assertThat(lastPage.last()).isTrue();

        Set<UUID> transactionIds = new HashSet<>();
        firstPage.transactions().forEach(entry -> transactionIds.add(entry.transactionId()));
        secondPage.transactions().forEach(entry -> transactionIds.add(entry.transactionId()));
        lastPage.transactions().forEach(entry -> transactionIds.add(entry.transactionId()));
        assertThat(transactionIds).hasSize(5);

        assertWalletError(
                WalletErrorCode.INVALID_REQUEST,
                () -> walletService.getHistory(playerId, -1, 20)
        );
        assertWalletError(
                WalletErrorCode.INVALID_REQUEST,
                () -> walletService.getHistory(playerId, 0, 101)
        );
    }

    @Test
    void concurrentCreditsProduceExactBalanceAndCompleteLedger() throws Exception {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        int requestCount = 20;

        List<MoneyMovementResult> results = runConcurrently(requestCount, index ->
                walletService.credit(
                        playerId,
                        command(10, "Concurrent reward", "reward-" + index, "credit-key-" + index)
                )
        );

        assertThat(results).hasSize(requestCount);
        assertThat(results).allMatch(result -> !result.replayed());
        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(200);
        assertThat(walletService.getHistory(playerId, 0, 100).totalElements())
                .isEqualTo(requestCount);
    }

    @Test
    void concurrentDebitsNeverOverdrawWallet() throws Exception {
        UUID playerId = createWalletWithBalance(100);
        int requestCount = 20;

        List<DebitOutcome> outcomes = runConcurrently(requestCount, index -> {
            try {
                walletService.debit(
                        playerId,
                        command(10, "Concurrent purchase", "purchase-" + index, "debit-key-" + index)
                );
                return DebitOutcome.success();
            } catch (WalletException exception) {
                return DebitOutcome.failure(exception.getCode());
            }
        });

        assertThat(outcomes).filteredOn(DebitOutcome::successful).hasSize(10);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.successful())
                .extracting(DebitOutcome::errorCode)
                .containsOnly(WalletErrorCode.INSUFFICIENT_FUNDS)
                .hasSize(10);
        assertThat(walletService.getBalance(playerId).balance()).isZero();
        assertThat(walletService.getHistory(playerId, 0, 100).totalElements()).isEqualTo(11);
    }

    @Test
    void concurrentDuplicateSubmissionsApplyExactlyOnce() throws Exception {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        MoneyMovementCommand request = command(
                7,
                "One reward",
                "reward-one",
                "one-shared-idempotency-key"
        );
        int requestCount = 20;

        List<MoneyMovementResult> results = runConcurrently(
                requestCount,
                ignored -> walletService.credit(playerId, request)
        );

        assertThat(results).extracting(MoneyMovementResult::transactionId).containsOnly(results.get(0).transactionId());
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results).filteredOn(MoneyMovementResult::replayed).hasSize(requestCount - 1);
        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(7);
        assertThat(walletService.getHistory(playerId, 0, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void concurrentWalletCreationCreatesExactlyOneWallet() throws Exception {
        UUID playerId = UUID.randomUUID();
        int requestCount = 20;

        List<String> outcomes = runConcurrently(requestCount, ignored -> {
            try {
                walletService.createWallet(playerId);
                return "CREATED";
            } catch (WalletException exception) {
                return exception.getCode().name();
            }
        });

        assertThat(outcomes).filteredOn("CREATED"::equals).hasSize(1);
        assertThat(outcomes)
                .filteredOn(WalletErrorCode.WALLET_ALREADY_EXISTS.name()::equals)
                .hasSize(requestCount - 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE player_id = ?",
                Long.class,
                playerId
        )).isEqualTo(1L);
    }

    @Test
    void refundsOriginalDebitExactlyOnceAndReplaysTheSameRequest() {
        UUID playerId = createWalletWithBalance(100);
        MoneyMovementResult debit = walletService.debit(
                playerId,
                command(40, "Shop purchase", "purchase-40", "purchase-40-debit")
        );
        RefundCommand request = new RefundCommand(
                "Purchase cancelled",
                "support-ticket-40",
                "purchase-40-refund"
        );

        MoneyMovementResult refund = walletService.refund(playerId, debit.transactionId(), request);
        MoneyMovementResult replay = walletService.refund(playerId, debit.transactionId(), request);

        assertThat(refund.type()).isEqualTo(TransactionType.REFUND);
        assertThat(refund.amount()).isEqualTo(40);
        assertThat(refund.balanceAfter()).isEqualTo(100);
        assertThat(refund.reversalOfTransactionId()).isEqualTo(debit.transactionId());
        assertThat(refund.replayed()).isFalse();
        assertThat(replay.transactionId()).isEqualTo(refund.transactionId());
        assertThat(replay.replayed()).isTrue();
        assertThat(walletService.getBalance(playerId).balance()).isEqualTo(100);

        TransactionHistory history = walletService.getHistory(playerId, 0, 20);
        assertThat(history.totalElements()).isEqualTo(3);
        assertThat(history.transactions())
                .filteredOn(entry -> entry.type() == TransactionType.REFUND)
                .singleElement()
                .satisfies(entry ->
                        assertThat(entry.reversalOfTransactionId()).isEqualTo(debit.transactionId())
                );

        assertWalletError(
                WalletErrorCode.TRANSACTION_ALREADY_REFUNDED,
                () -> walletService.refund(
                        playerId,
                        debit.transactionId(),
                        new RefundCommand("Another attempt", "ticket-41", "another-refund-key")
                )
        );
        assertWalletError(
                WalletErrorCode.IDEMPOTENCY_CONFLICT,
                () -> walletService.refund(
                        playerId,
                        debit.transactionId(),
                        new RefundCommand("Changed reason", "support-ticket-40", "purchase-40-refund")
                )
        );
    }

    @Test
    void rejectsMissingCrossWalletAndNonDebitRefundSources() {
        UUID firstPlayerId = createWalletWithBalance(50);
        UUID secondPlayerId = walletService.createWallet(UUID.randomUUID()).playerId();
        UUID creditTransactionId = walletService.getHistory(firstPlayerId, 0, 20)
                .transactions()
                .stream()
                .findFirst()
                .orElseThrow()
                .transactionId();
        RefundCommand request = new RefundCommand("Invalid refund", "ticket-invalid", "refund-invalid");

        assertWalletError(
                WalletErrorCode.TRANSACTION_NOT_REFUNDABLE,
                () -> walletService.refund(firstPlayerId, creditTransactionId, request)
        );
        assertWalletError(
                WalletErrorCode.TRANSACTION_NOT_FOUND,
                () -> walletService.refund(firstPlayerId, UUID.randomUUID(), request)
        );
        assertWalletError(
                WalletErrorCode.TRANSACTION_NOT_FOUND,
                () -> walletService.refund(secondPlayerId, creditTransactionId, request)
        );
    }

    @Test
    void concurrentRefundAttemptsApplyExactlyOnce() throws Exception {
        UUID playerId = createWalletWithBalance(100);
        MoneyMovementResult debit = walletService.debit(
                playerId,
                command(35, "Shop purchase", "purchase-concurrent", "concurrent-debit")
        );
        int requestCount = 20;

        List<String> outcomes = runConcurrently(requestCount, index -> {
            try {
                walletService.refund(
                        playerId,
                        debit.transactionId(),
                        new RefundCommand(
                                "Concurrent cancellation",
                                "ticket-" + index,
                                "concurrent-refund-" + index
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
                .filteredOn(entry -> entry.type() == TransactionType.REFUND)
                .hasSize(1);
    }

    private UUID createWalletWithBalance(long balance) {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(
                playerId,
                command(balance, "Initial balance", "initial-1", "initial-credit")
        );
        return playerId;
    }

    private static MoneyMovementCommand command(
            long amount,
            String reason,
            String referenceId,
            String idempotencyKey
    ) {
        return new MoneyMovementCommand(amount, reason, referenceId, idempotencyKey);
    }

    private static void assertWalletError(WalletErrorCode expectedCode, Runnable action) {
        assertThatExceptionOfType(WalletException.class)
                .isThrownBy(action::run)
                .satisfies(exception -> assertThat(exception.getCode()).isEqualTo(expectedCode));
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

            return futures.stream().map(future -> getFuture(future, 20)).toList();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static <T> T getFuture(Future<T> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent operation failed", exception);
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
