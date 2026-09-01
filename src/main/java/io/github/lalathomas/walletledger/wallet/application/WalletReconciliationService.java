package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.Wallet;
import io.github.lalathomas.walletledger.wallet.persistence.FundReservationRepository;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerBalanceProjection;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerEntryRepository;
import io.github.lalathomas.walletledger.wallet.persistence.ReservationBalanceProjection;
import io.github.lalathomas.walletledger.wallet.persistence.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.UUID;

@Service
public class WalletReconciliationService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FundReservationRepository reservationRepository;

    public WalletReconciliationService(
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository,
            FundReservationRepository reservationRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationResult reconcile(UUID playerId) {
        UUID validatedPlayerId = WalletInputValidator.requireId(playerId, "playerId");
        Wallet wallet = walletRepository.findByPlayerId(validatedPlayerId)
                .orElseThrow(() -> WalletException.walletNotFound(validatedPlayerId));
        LedgerBalanceProjection ledger = ledgerEntryRepository.calculateLedgerBalance(wallet.getId());
        ReservationBalanceProjection reservations =
                reservationRepository.calculateActiveReservations(wallet.getId());

        BigInteger calculatedBalance = ledger.getCalculatedBalance().toBigIntegerExact();
        BigInteger calculatedReserved =
                reservations.getCalculatedReservedBalance().toBigIntegerExact();
        boolean balanceMatches = calculatedBalance.equals(BigInteger.valueOf(wallet.getBalance()));
        boolean reservedMatches = calculatedReserved.equals(
                BigInteger.valueOf(wallet.getReservedBalance())
        );
        return new ReconciliationResult(
                validatedPlayerId, wallet.getBalance(), calculatedBalance,
                wallet.getReservedBalance(), calculatedReserved, wallet.getAvailableBalance(),
                ledger.getTransactionCount(), reservations.getActiveReservationCount(),
                balanceMatches, reservedMatches, balanceMatches && reservedMatches
        );
    }
}
