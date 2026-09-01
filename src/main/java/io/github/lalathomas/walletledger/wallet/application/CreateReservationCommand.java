package io.github.lalathomas.walletledger.wallet.application;

public record CreateReservationCommand(
        long amount,
        String reason,
        String referenceId,
        String idempotencyKey
) {
}
