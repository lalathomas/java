package io.github.lalathomas.walletledger.wallet.application;

public enum WalletErrorCode {
    WALLET_NOT_FOUND,
    WALLET_ALREADY_EXISTS,
    INSUFFICIENT_FUNDS,
    IDEMPOTENCY_CONFLICT,
    INVALID_AMOUNT,
    INVALID_REQUEST,
    BALANCE_OVERFLOW
}
