package io.github.lalathomas.walletledger.wallet.application;

import java.util.List;
import java.util.UUID;

public record TransactionHistory(
        UUID playerId,
        List<LedgerEntryView> transactions,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public TransactionHistory {
        transactions = List.copyOf(transactions);
    }
}
