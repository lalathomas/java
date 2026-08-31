package io.github.lalathomas.walletledger.wallet.api;

import io.github.lalathomas.walletledger.wallet.application.TransactionHistory;

import java.util.List;
import java.util.UUID;

public record TransactionHistoryResponse(
        UUID playerId,
        List<TransactionResponse> transactions,
        PageMetadata page
) {
    public TransactionHistoryResponse {
        transactions = List.copyOf(transactions);
    }

    public static TransactionHistoryResponse from(TransactionHistory history) {
        return new TransactionHistoryResponse(
                history.playerId(),
                history.transactions().stream().map(TransactionResponse::from).toList(),
                new PageMetadata(
                        history.page(),
                        history.size(),
                        history.totalElements(),
                        history.totalPages(),
                        history.first(),
                        history.last()
                )
        );
    }

    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last
    ) {
    }
}
