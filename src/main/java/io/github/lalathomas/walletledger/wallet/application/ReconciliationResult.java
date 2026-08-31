package io.github.lalathomas.walletledger.wallet.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

public record ReconciliationResult(
        UUID playerId,
        long storedBalance,
        BigInteger calculatedBalance,
        boolean consistent,
        long transactionCount,
        Instant checkedAt
) {
}
