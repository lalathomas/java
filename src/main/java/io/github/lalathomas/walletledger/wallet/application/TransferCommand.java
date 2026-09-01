package io.github.lalathomas.walletledger.wallet.application;

import java.util.UUID;

public record TransferCommand(
        UUID destinationPlayerId,
        long amount,
        String reason,
        String referenceId,
        String idempotencyKey
) {
}
