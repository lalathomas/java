package io.github.lalathomas.walletledger.wallet.application;

import java.util.UUID;
import java.util.regex.Pattern;

final class WalletInputValidator {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    private WalletInputValidator() {
    }

    static UUID requireId(UUID value, String name) {
        if (value == null) {
            throw WalletException.invalidRequest(name + " is required");
        }
        return value;
    }

    static long requirePositive(long amount) {
        if (amount <= 0) {
            throw WalletException.invalidAmount(amount);
        }
        return amount;
    }

    static String reason(String value) {
        return required(value, "reason", 255);
    }

    static String reference(String value) {
        return required(value, "referenceId", 100);
    }

    static String key(String value) {
        String normalized = required(value, "Idempotency-Key", 100);
        if (!KEY_PATTERN.matcher(normalized).matches()) {
            throw WalletException.invalidRequest(
                    "Idempotency-Key has an invalid format"
            );
        }
        return normalized;
    }

    private static String required(String value, String name, int maximum) {
        if (value == null || value.isBlank()) {
            throw WalletException.invalidRequest(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw WalletException.invalidRequest(name + " must not exceed " + maximum + " characters");
        }
        return normalized;
    }
}
