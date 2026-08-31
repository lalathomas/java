package io.github.lalathomas.walletledger.wallet.application;

public record RefundCommand(
        String reason,
        String referenceId,
        String idempotencyKey
) {
}
