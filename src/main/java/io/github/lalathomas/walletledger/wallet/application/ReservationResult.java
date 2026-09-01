package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResult(
        UUID reservationId,
        UUID playerId,
        long amount,
        ReservationStatus status,
        long balance,
        long reservedBalance,
        long availableBalance,
        UUID transactionId,
        String reason,
        String referenceId,
        Instant createdAt,
        Instant updatedAt,
        boolean replayed
) {
}
