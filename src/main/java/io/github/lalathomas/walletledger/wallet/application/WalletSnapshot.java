package io.github.lalathomas.walletledger.wallet.application;

import java.time.Instant;
import java.util.UUID;

public record WalletSnapshot(
        UUID playerId,
        long balance,
        Instant createdAt,
        Instant updatedAt
) {
}
