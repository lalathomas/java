package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.ReservationResult;
import io.github.lalathomas.walletledger.wallet.domain.ReservationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId, UUID playerId, long amount, ReservationStatus status,
        long balance, long reservedBalance, long availableBalance, UUID transactionId,
        String reason, String referenceId, Instant createdAt, Instant updatedAt, boolean replayed
) {
    public static ReservationResponse from(ReservationResult result) {
        return new ReservationResponse(
                result.reservationId(), result.playerId(), result.amount(), result.status(),
                result.balance(), result.reservedBalance(), result.availableBalance(), result.transactionId(),
                result.reason(), result.referenceId(), result.createdAt(), result.updatedAt(), result.replayed()
        );
    }
}
