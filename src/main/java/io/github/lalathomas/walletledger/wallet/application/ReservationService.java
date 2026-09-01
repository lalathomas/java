package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.FundReservation;
import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import io.github.lalathomas.walletledger.wallet.domain.ReservationStatus;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import io.github.lalathomas.walletledger.wallet.domain.Wallet;
import io.github.lalathomas.walletledger.wallet.persistence.FundReservationRepository;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerEntryRepository;
import io.github.lalathomas.walletledger.wallet.persistence.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ReservationService {

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final FundReservationRepository reservationRepository;

    public ReservationService(
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository,
            FundReservationRepository reservationRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.reservationRepository = reservationRepository;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResult reserve(UUID playerId, CreateReservationCommand command) {
        UUID validatedPlayerId = WalletInputValidator.requireId(playerId, "playerId");
        if (command == null) {
            throw WalletException.invalidRequest("Reservation request is required");
        }
        long amount = WalletInputValidator.requirePositive(command.amount());
        String reason = WalletInputValidator.reason(command.reason());
        String reference = WalletInputValidator.reference(command.referenceId());
        String key = WalletInputValidator.key(command.idempotencyKey());
        Wallet wallet = lockedWallet(validatedPlayerId);

        LedgerEntry existing = existing(wallet, key);
        if (existing != null) {
            UUID reservationId = existing.getReservationId();
            if (!existing.representsReservationAction(
                    reservationId, TransactionType.RESERVE, amount, reason, reference
            )) {
                throw WalletException.idempotencyConflict(validatedPlayerId, key);
            }
            FundReservation reservation = reservation(wallet, reservationId, validatedPlayerId);
            return result(reservation, wallet, existing, true);
        }
        if (amount > wallet.getAvailableBalance()) {
            throw WalletException.insufficientFunds(
                    validatedPlayerId, amount, wallet.getAvailableBalance()
            );
        }

        wallet.reserve(amount);
        FundReservation reservation = reservationRepository.save(
                new FundReservation(wallet, amount, reason, reference)
        );
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.reservationAction(
                wallet, TransactionType.RESERVE, amount, wallet.getBalance(),
                reason, reference, key, reservation.getId()
        ));
        return result(reservation, wallet, entry, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResult capture(
            UUID playerId, UUID reservationId, ReservationActionCommand command
    ) {
        return complete(playerId, reservationId, command, TransactionType.DEBIT);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResult release(
            UUID playerId, UUID reservationId, ReservationActionCommand command
    ) {
        return complete(playerId, reservationId, command, TransactionType.RELEASE);
    }

    private ReservationResult complete(
            UUID playerId, UUID reservationId, ReservationActionCommand command,
            TransactionType actionType
    ) {
        UUID validatedPlayerId = WalletInputValidator.requireId(playerId, "playerId");
        UUID validatedReservationId = WalletInputValidator.requireId(reservationId, "reservationId");
        if (command == null) {
            throw WalletException.invalidRequest("Reservation action request is required");
        }
        String reason = WalletInputValidator.reason(command.reason());
        String reference = WalletInputValidator.reference(command.referenceId());
        String key = WalletInputValidator.key(command.idempotencyKey());
        Wallet wallet = lockedWallet(validatedPlayerId);
        FundReservation reservation = reservation(wallet, validatedReservationId, validatedPlayerId);

        LedgerEntry existing = existing(wallet, key);
        if (existing != null) {
            if (!existing.representsReservationAction(
                    validatedReservationId, actionType, reservation.getAmount(), reason, reference
            )) {
                throw WalletException.idempotencyConflict(validatedPlayerId, key);
            }
            return result(reservation, wallet, existing, true);
        }
        if (reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw WalletException.reservationNotActive(
                    validatedReservationId, reservation.getStatus()
            );
        }

        long balanceAfter = wallet.getBalance();
        if (actionType == TransactionType.DEBIT) {
            balanceAfter = wallet.captureReservation(reservation.getAmount());
            reservation.capture();
        } else {
            wallet.releaseReservation(reservation.getAmount());
            reservation.release();
        }
        reservationRepository.save(reservation);
        LedgerEntry entry = ledgerEntryRepository.save(LedgerEntry.reservationAction(
                wallet, actionType, reservation.getAmount(), balanceAfter,
                reason, reference, key, validatedReservationId
        ));
        return result(reservation, wallet, entry, false);
    }

    private Wallet lockedWallet(UUID playerId) {
        return walletRepository.findByPlayerIdForUpdate(playerId)
                .orElseThrow(() -> WalletException.walletNotFound(playerId));
    }

    private LedgerEntry existing(Wallet wallet, String key) {
        return ledgerEntryRepository.findByWalletIdAndIdempotencyKey(wallet.getId(), key)
                .orElse(null);
    }

    private FundReservation reservation(Wallet wallet, UUID reservationId, UUID playerId) {
        return reservationRepository.findByIdAndWalletId(reservationId, wallet.getId())
                .orElseThrow(() -> WalletException.reservationNotFound(playerId, reservationId));
    }

    private static ReservationResult result(
            FundReservation reservation, Wallet wallet, LedgerEntry entry, boolean replayed
    ) {
        return new ReservationResult(
                reservation.getId(), wallet.getPlayerId(), reservation.getAmount(),
                reservation.getStatus(), wallet.getBalance(), wallet.getReservedBalance(),
                wallet.getAvailableBalance(), entry.getId(), entry.getReason(),
                entry.getReferenceId(), reservation.getCreatedAt(), reservation.getUpdatedAt(), replayed
        );
    }
}
