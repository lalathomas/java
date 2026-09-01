package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.TransferResult;

import java.time.Instant;
import java.util.UUID;

public record TransferResponse(
        UUID transferId, UUID sourcePlayerId, UUID destinationPlayerId, long amount,
        UUID sourceTransactionId, UUID destinationTransactionId,
        long sourceBalanceAfter, long destinationBalanceAfter,
        String reason, String referenceId, Instant createdAt, boolean replayed
) {
    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(
                result.transferId(), result.sourcePlayerId(), result.destinationPlayerId(), result.amount(),
                result.sourceTransactionId(), result.destinationTransactionId(),
                result.sourceBalanceAfter(), result.destinationBalanceAfter(),
                result.reason(), result.referenceId(), result.createdAt(), result.replayed()
        );
    }
}
