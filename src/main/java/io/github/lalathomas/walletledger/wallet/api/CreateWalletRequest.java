package io.github.lalathomas.walletledger.wallet.api;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateWalletRequest(
        @NotNull(message = "playerId is required")
        UUID playerId
) {
}
