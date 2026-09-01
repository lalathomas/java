package io.github.lalathomas.walletledger.wallet.application;

public record ReservationActionCommand(
        String reason,
        String referenceId,
        String idempotencyKey
) {
}
