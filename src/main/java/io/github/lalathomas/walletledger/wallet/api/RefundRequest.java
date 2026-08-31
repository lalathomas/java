package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.RefundCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RefundRequest(
        @NotBlank(message = "reason must not be blank")
        @Size(max = 255, message = "reason must not exceed 255 characters")
        String reason,

        @NotBlank(message = "referenceId must not be blank")
        @Size(max = 100, message = "referenceId must not exceed 100 characters")
        String referenceId
) {
    public RefundCommand toCommand(String idempotencyKey) {
        return new RefundCommand(reason, referenceId, idempotencyKey);
    }
}
