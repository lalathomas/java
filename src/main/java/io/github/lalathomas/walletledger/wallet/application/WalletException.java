package io.github.lalathomas.walletledger.wallet.application;

import java.util.Map;
import java.util.UUID;

public final class WalletException extends RuntimeException {

    private final WalletErrorCode code;
    private final Map<String, Object> details;

    private WalletException(WalletErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public static WalletException walletNotFound(UUID playerId) {
        return new WalletException(
                WalletErrorCode.WALLET_NOT_FOUND,
                "Wallet was not found for player " + playerId,
                Map.of("playerId", playerId.toString())
        );
    }

    public static WalletException walletAlreadyExists(UUID playerId) {
        return new WalletException(
                WalletErrorCode.WALLET_ALREADY_EXISTS,
                "A wallet already exists for player " + playerId,
                Map.of("playerId", playerId.toString())
        );
    }

    public static WalletException transactionNotFound(UUID playerId, UUID transactionId) {
        return new WalletException(
                WalletErrorCode.TRANSACTION_NOT_FOUND,
                "Transaction was not found for this wallet",
                Map.of(
                        "playerId", playerId.toString(),
                        "transactionId", transactionId.toString()
                )
        );
    }

    public static WalletException transactionNotRefundable(UUID transactionId) {
        return new WalletException(
                WalletErrorCode.TRANSACTION_NOT_REFUNDABLE,
                "Only debit transactions can be refunded",
                Map.of("transactionId", transactionId.toString())
        );
    }

    public static WalletException debitAlreadyRefunded(UUID playerId, UUID debitTransactionId) {
        return new WalletException(
                WalletErrorCode.DEBIT_ALREADY_REFUNDED,
                "The debit transaction has already been refunded",
                Map.of(
                        "playerId", playerId.toString(),
                        "debitTransactionId", debitTransactionId.toString()
                )
        );
    }

    public static WalletException insufficientFunds(
            UUID playerId,
            long requestedAmount,
            long availableBalance
    ) {
        return new WalletException(
                WalletErrorCode.INSUFFICIENT_FUNDS,
                "The wallet does not have enough funds for this debit",
                Map.of(
                        "playerId", playerId.toString(),
                        "requestedAmount", requestedAmount,
                        "availableBalance", availableBalance
                )
        );
    }

    public static WalletException idempotencyConflict(UUID playerId, String idempotencyKey) {
        return new WalletException(
                WalletErrorCode.IDEMPOTENCY_CONFLICT,
                "The idempotency key was already used for a different request",
                Map.of(
                        "playerId", playerId.toString(),
                        "idempotencyKey", idempotencyKey
                )
        );
    }

    public static WalletException invalidAmount(long amount) {
        return new WalletException(
                WalletErrorCode.INVALID_AMOUNT,
                "Amount must be greater than zero",
                Map.of("amount", amount)
        );
    }

    public static WalletException invalidRequest(String message) {
        return new WalletException(WalletErrorCode.INVALID_REQUEST, message, Map.of());
    }

    public static WalletException balanceOverflow(UUID playerId) {
        return new WalletException(
                WalletErrorCode.BALANCE_OVERFLOW,
                "The credit would exceed the largest supported wallet balance",
                Map.of("playerId", playerId.toString())
        );
    }

    public WalletErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
