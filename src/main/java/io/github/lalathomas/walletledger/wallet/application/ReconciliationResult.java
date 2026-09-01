package io.github.lalathomas.walletledger.wallet.application;

import java.math.BigInteger;
import java.util.UUID;

public record ReconciliationResult(
        UUID playerId,
        long storedBalance,
        BigInteger calculatedBalance,
        long storedReservedBalance,
        BigInteger calculatedReservedBalance,
        long availableBalance,
        long transactionCount,
        long activeReservationCount,
        boolean balanceMatches,
        boolean reservedBalanceMatches,
        boolean consistent
) {
}
