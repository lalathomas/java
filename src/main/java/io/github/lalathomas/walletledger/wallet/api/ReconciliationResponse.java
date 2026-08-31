package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.ReconciliationResult;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationResponse(
        UUID playerId,
        long storedBalance,
        BigInteger calculatedBalance,
        boolean consistent,
        long transactionCount,
        Instant checkedAt
) {
    public static ReconciliationResponse from(ReconciliationResult result) {
        return new ReconciliationResponse(
                result.playerId(),
                result.storedBalance(),
                result.calculatedBalance(),
                result.consistent(),
                result.transactionCount(),
                result.checkedAt()
        );
    }
}
