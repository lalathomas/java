package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.CreateReservationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateReservationRequest(
        @NotNull(message = "amount is required") @Positive(message = "amount must be greater than zero") Long amount,
        @NotBlank(message = "reason must not be blank") @Size(max = 255) String reason,
        @NotBlank(message = "referenceId must not be blank") @Size(max = 100) String referenceId
) {
    public CreateReservationCommand toCommand(String idempotencyKey) {
        return new CreateReservationCommand(amount, reason, referenceId, idempotencyKey);
    }
}
