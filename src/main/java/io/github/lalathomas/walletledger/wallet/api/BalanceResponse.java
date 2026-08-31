package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.WalletSnapshot;

import java.time.Instant;
import java.util.UUID;

public record BalanceResponse(
        UUID playerId,
        long balance,
        Instant updatedAt
) {
    public static BalanceResponse from(WalletSnapshot snapshot) {
        return new BalanceResponse(
                snapshot.playerId(),
                snapshot.balance(),
                snapshot.updatedAt()
        );
    }
}
