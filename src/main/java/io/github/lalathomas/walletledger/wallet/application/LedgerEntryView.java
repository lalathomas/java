package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryView(
        UUID transactionId,
        TransactionType type,
        long amount,
        long balanceAfter,
        String reason,
        String referenceId,
        UUID reversalOfTransactionId,
        UUID transferId,
        UUID reservationId,
        Instant createdAt
) {
}
