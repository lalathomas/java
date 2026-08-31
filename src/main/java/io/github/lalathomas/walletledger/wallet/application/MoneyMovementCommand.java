package io.github.lalathomas.walletledger.wallet.application;

public record MoneyMovementCommand(
        long amount,
        String reason,
        String referenceId,
        String idempotencyKey
) {
}
