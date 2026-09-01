package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.MoneyMovementResult;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record MoneyMovementResponse(
        UUID transactionId,
        UUID playerId,
        TransactionType type,
        long amount,
        long balanceAfter,
        String reason,
        String referenceId,
        String idempotencyKey,
        UUID reversalOfTransactionId,
        Instant createdAt,
        boolean replayed
) {
    public static MoneyMovementResponse from(MoneyMovementResult result) {
        return new MoneyMovementResponse(
                result.transactionId(),
                result.playerId(),
                result.type(),
                result.amount(),
                result.balanceAfter(),
                result.reason(),
                result.referenceId(),
                result.idempotencyKey(),
                result.reversalOfTransactionId(),
                result.createdAt(),
                result.replayed()
        );
    }
}
