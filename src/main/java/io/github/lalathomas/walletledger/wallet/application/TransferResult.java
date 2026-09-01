package io.github.lalathomas.walletledger.wallet.application;

import java.time.Instant;
import java.util.UUID;

public record TransferResult(
        UUID transferId,
        UUID sourcePlayerId,
        UUID destinationPlayerId,
        long amount,
        UUID sourceTransactionId,
        UUID destinationTransactionId,
        long sourceBalanceAfter,
        long destinationBalanceAfter,
        String reason,
        String referenceId,
        Instant createdAt,
        boolean replayed
) {
}
