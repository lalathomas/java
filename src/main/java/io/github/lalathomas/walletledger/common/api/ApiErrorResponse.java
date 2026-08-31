package io.github.lalathomas.walletledger.common.api;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        Map<String, String> fieldErrors,
        Map<String, Object> details
) {
    public ApiErrorResponse {
        fieldErrors = Map.copyOf(fieldErrors);
        details = Map.copyOf(details);
    }
}
