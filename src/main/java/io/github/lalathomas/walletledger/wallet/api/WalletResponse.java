package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.WalletSnapshot;

import java.time.Instant;
import java.util.UUID;

public record WalletResponse(
        UUID playerId,
        long balance,
        Instant createdAt,
        Instant updatedAt
) {
    public static WalletResponse from(WalletSnapshot snapshot) {
        return new WalletResponse(
                snapshot.playerId(),
                snapshot.balance(),
                snapshot.createdAt(),
                snapshot.updatedAt()
        );
    }
}
