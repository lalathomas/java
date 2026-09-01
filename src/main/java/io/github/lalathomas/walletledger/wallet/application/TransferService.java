package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import io.github.lalathomas.walletledger.wallet.domain.Wallet;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerEntryRepository;
import io.github.lalathomas.walletledger.wallet.persistence.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public TransferService(
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult transfer(UUID sourcePlayerId, TransferCommand command) {
        UUID sourceId = WalletInputValidator.requireId(sourcePlayerId, "sourcePlayerId");
        if (command == null) {
            throw WalletException.invalidRequest("Transfer request is required");
        }
        UUID destinationId = WalletInputValidator.requireId(
                command.destinationPlayerId(), "destinationPlayerId"
        );
        if (sourceId.equals(destinationId)) {
            throw WalletException.invalidRequest("Source and destination wallets must be different");
        }
        long amount = WalletInputValidator.requirePositive(command.amount());
        String reason = WalletInputValidator.reason(command.reason());
        String reference = WalletInputValidator.reference(command.referenceId());
        String key = WalletInputValidator.key(command.idempotencyKey());

        List<Wallet> locked = walletRepository.findAllByPlayerIdForUpdate(List.of(sourceId, destinationId));
        Wallet source = wallet(locked, sourceId);
        Wallet destination = wallet(locked, destinationId);

        LedgerEntry existing = ledgerEntryRepository
                .findByWalletIdAndIdempotencyKey(source.getId(), key)
                .orElse(null);
        if (existing != null) {
            if (!existing.representsTransfer(amount, reason, reference, destination.getId())) {
                throw WalletException.idempotencyConflict(sourceId, key);
            }
            return replay(existing, sourceId, destinationId);
        }

        if (amount > source.getAvailableBalance()) {
            throw WalletException.insufficientFunds(sourceId, amount, source.getAvailableBalance());
        }
        long sourceBalance = source.debit(amount);
        long destinationBalance;
        try {
            destinationBalance = destination.credit(amount);
        } catch (ArithmeticException exception) {
            throw WalletException.balanceOverflow(destinationId);
        }

        UUID transferId = UUID.randomUUID();
        LedgerEntry outgoing = ledgerEntryRepository.save(LedgerEntry.transfer(
                source, TransactionType.TRANSFER_OUT, amount, sourceBalance,
                reason, reference, key, transferId, destination.getId()
        ));
        LedgerEntry incoming = ledgerEntryRepository.save(LedgerEntry.transfer(
                destination, TransactionType.TRANSFER_IN, amount, destinationBalance,
                reason, reference, "transfer:" + transferId, transferId, source.getId()
        ));
        return result(outgoing, incoming, sourceId, destinationId, false);
    }

    private Wallet wallet(List<Wallet> wallets, UUID playerId) {
        return wallets.stream()
                .filter(candidate -> candidate.getPlayerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> WalletException.walletNotFound(playerId));
    }

    private TransferResult replay(
            LedgerEntry outgoing, UUID sourcePlayerId, UUID destinationPlayerId
    ) {
        List<LedgerEntry> pair = ledgerEntryRepository.findByTransferId(outgoing.getTransferId());
        LedgerEntry incoming = pair.stream()
                .filter(entry -> entry.getType() == TransactionType.TRANSFER_IN)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Transfer credit entry is missing"));
        return result(outgoing, incoming, sourcePlayerId, destinationPlayerId, true);
    }

    private static TransferResult result(
            LedgerEntry outgoing, LedgerEntry incoming,
            UUID sourcePlayerId, UUID destinationPlayerId, boolean replayed
    ) {
        return new TransferResult(
                outgoing.getTransferId(), sourcePlayerId, destinationPlayerId,
                outgoing.getAmount(), outgoing.getId(), incoming.getId(),
                outgoing.getBalanceAfter(), incoming.getBalanceAfter(),
                outgoing.getReason(), outgoing.getReferenceId(), outgoing.getCreatedAt(), replayed
        );
    }
}
