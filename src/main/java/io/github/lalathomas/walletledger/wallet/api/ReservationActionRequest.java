package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.ReservationActionCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReservationActionRequest(
        @NotBlank(message = "reason must not be blank") @Size(max = 255) String reason,
        @NotBlank(message = "referenceId must not be blank") @Size(max = 100) String referenceId
) {
    public ReservationActionCommand toCommand(String idempotencyKey) {
        return new ReservationActionCommand(reason, referenceId, idempotencyKey);
    }
}
