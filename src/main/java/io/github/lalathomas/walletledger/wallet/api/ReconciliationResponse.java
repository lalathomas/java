package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.ReconciliationResult;

import java.math.BigInteger;
import java.util.UUID;

public record ReconciliationResponse(
        UUID playerId, long storedBalance, BigInteger calculatedBalance,
        long storedReservedBalance, BigInteger calculatedReservedBalance,
        long availableBalance, long transactionCount, long activeReservationCount,
        boolean balanceMatches, boolean reservedBalanceMatches, boolean consistent
) {
    public static ReconciliationResponse from(ReconciliationResult result) {
        return new ReconciliationResponse(
                result.playerId(), result.storedBalance(), result.calculatedBalance(),
                result.storedReservedBalance(), result.calculatedReservedBalance(),
                result.availableBalance(), result.transactionCount(), result.activeReservationCount(),
                result.balanceMatches(), result.reservedBalanceMatches(), result.consistent()
        );
    }
}
