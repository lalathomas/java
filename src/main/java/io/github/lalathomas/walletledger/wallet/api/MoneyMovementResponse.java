package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.MoneyMovementResult;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(
                description = "Original debit transaction ID for a refund credit; null otherwise",
                types = {"string", "null"},
                format = "uuid"
        )
        UUID refundedDebitId,
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
                result.refundedDebitId(),
                result.createdAt(),
                result.replayed()
        );
    }
}
