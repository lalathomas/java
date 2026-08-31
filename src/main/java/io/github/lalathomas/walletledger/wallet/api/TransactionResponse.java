package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.LedgerEntryView;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;

import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        TransactionType type,
        long amount,
        long balanceAfter,
        String reason,
        String referenceId,
        Instant createdAt
) {
    public static TransactionResponse from(LedgerEntryView entry) {
        return new TransactionResponse(
                entry.transactionId(),
                entry.type(),
                entry.amount(),
                entry.balanceAfter(),
                entry.reason(),
                entry.referenceId(),
                entry.createdAt()
        );
    }
}
