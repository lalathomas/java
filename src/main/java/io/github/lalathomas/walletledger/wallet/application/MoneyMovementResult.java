package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record MoneyMovementResult(
        UUID transactionId,
        UUID playerId,
        TransactionType type,
        long amount,
        long balanceAfter,
        String reason,
        String referenceId,
        String idempotencyKey,
        UUID refundedDebitId,
        Instant createdAt,
        boolean replayed
) {
}
