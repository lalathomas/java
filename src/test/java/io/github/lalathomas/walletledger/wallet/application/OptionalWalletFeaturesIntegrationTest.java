package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.ReservationStatus;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigInteger;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

@SpringBootTest
@ActiveProfiles("test")
class OptionalWalletFeaturesIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired TransferService transferService;
    @Autowired ReservationService reservationService;
    @Autowired WalletReconciliationService reconciliationService;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM fund_reservations");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    @Test
    void transfersAtomicallyAndReplaysWithoutMovingFundsTwice() {
        UUID source = walletService.createWallet(UUID.randomUUID()).playerId();
        UUID destination = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(source, movement(100, "Initial funds", "initial", "initial-credit"));
        TransferCommand command = new TransferCommand(
                destination, 35, "Player gift", "gift-1", "transfer-key-1"
        );

        TransferResult first = transferService.transfer(source, command);
        TransferResult replay = transferService.transfer(source, command);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.transferId()).isEqualTo(first.transferId());
        assertThat(walletService.getBalance(source).balance()).isEqualTo(65);
        assertThat(walletService.getBalance(destination).balance()).isEqualTo(35);
        assertThat(walletService.getHistory(source, 0, 20).transactions())
                .anyMatch(entry -> entry.type() == TransactionType.TRANSFER_OUT);
        assertThat(walletService.getHistory(destination, 0, 20).transactions())
                .anyMatch(entry -> entry.type() == TransactionType.TRANSFER_IN);
    }

    @Test
    void reservationProtectsFundsAndCanBeCaptured() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(playerId, movement(100, "Initial funds", "initial", "credit-1"));

        ReservationResult held = reservationService.reserve(
                playerId,
                new CreateReservationCommand(60, "Auction bid", "bid-7", "reserve-1")
        );
        assertThat(held.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(held.reservedBalance()).isEqualTo(60);
        assertThat(held.availableBalance()).isEqualTo(40);

        assertThatExceptionOfType(WalletException.class)
                .isThrownBy(() -> walletService.debit(
                        playerId, movement(41, "Purchase", "purchase-1", "blocked-debit")
                ))
                .satisfies(error -> assertThat(error.getCode())
                        .isEqualTo(WalletErrorCode.INSUFFICIENT_FUNDS));

        ReservationResult captured = reservationService.capture(
                playerId,
                held.reservationId(),
                new ReservationActionCommand("Auction won", "bid-7-capture", "capture-1")
        );
        assertThat(captured.status()).isEqualTo(ReservationStatus.CAPTURED);
        assertThat(captured.balance()).isEqualTo(40);
        assertThat(captured.reservedBalance()).isZero();
        assertThat(walletService.getHistory(playerId, 0, 20).transactions())
                .anyMatch(entry -> entry.type() == TransactionType.DEBIT
                        && held.reservationId().equals(entry.reservationId()));
    }

    @Test
    void reservationCanBeReleasedButNotCompletedTwice() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(playerId, movement(50, "Initial funds", "initial", "credit-1"));
        ReservationResult held = reservationService.reserve(
                playerId,
                new CreateReservationCommand(30, "Pending order", "order-1", "reserve-1")
        );

        ReservationResult released = reservationService.release(
                playerId, held.reservationId(),
                new ReservationActionCommand("Order cancelled", "order-1-release", "release-1")
        );
        assertThat(released.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(released.balance()).isEqualTo(50);
        assertThat(released.availableBalance()).isEqualTo(50);
        assertThatExceptionOfType(WalletException.class)
                .isThrownBy(() -> reservationService.capture(
                        playerId, held.reservationId(),
                        new ReservationActionCommand("Late capture", "late", "capture-late")
                ))
                .satisfies(error -> assertThat(error.getCode())
                        .isEqualTo(WalletErrorCode.RESERVATION_NOT_ACTIVE));
    }

    @Test
    void reconciliationMatchesLedgerAndActiveReservations() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        walletService.credit(playerId, movement(90, "Initial funds", "initial", "credit-1"));
        walletService.debit(playerId, movement(20, "Purchase", "purchase", "debit-1"));
        reservationService.reserve(
                playerId,
                new CreateReservationCommand(15, "Bid", "bid-1", "reserve-1")
        );

        ReconciliationResult result = reconciliationService.reconcile(playerId);

        assertThat(result.storedBalance()).isEqualTo(70);
        assertThat(result.calculatedBalance()).isEqualTo(BigInteger.valueOf(70));
        assertThat(result.storedReservedBalance()).isEqualTo(15);
        assertThat(result.calculatedReservedBalance()).isEqualTo(BigInteger.valueOf(15));
        assertThat(result.availableBalance()).isEqualTo(55);
        assertThat(result.consistent()).isTrue();
    }

    private static MoneyMovementCommand movement(
            long amount, String reason, String reference, String key
    ) {
        return new MoneyMovementCommand(amount, reason, reference, key);
    }
}
