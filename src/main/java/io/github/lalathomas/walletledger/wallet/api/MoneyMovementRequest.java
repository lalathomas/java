package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.MoneyMovementCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MoneyMovementRequest(
        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        Long amount,

        @NotBlank(message = "reason must not be blank")
        @Size(max = 255, message = "reason must not exceed 255 characters")
        String reason,

        @NotBlank(message = "referenceId must not be blank")
        @Size(max = 100, message = "referenceId must not exceed 100 characters")
        String referenceId
) {
    public MoneyMovementCommand toCommand(String idempotencyKey) {
        return new MoneyMovementCommand(amount, reason, referenceId, idempotencyKey);
    }
}
