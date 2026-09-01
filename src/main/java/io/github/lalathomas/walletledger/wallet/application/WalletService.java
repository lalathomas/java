package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import io.github.lalathomas.walletledger.wallet.domain.Wallet;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerEntryRepository;
import io.github.lalathomas.walletledger.wallet.persistence.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 255;
    private static final int MAX_REFERENCE_LENGTH = 100;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}");

    private final WalletRepository walletRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletService(
            WalletRepository walletRepository,
            LedgerEntryRepository ledgerEntryRepository
    ) {
        this.walletRepository = walletRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional
    public WalletSnapshot createWallet(UUID playerId) {
        UUID validatedPlayerId = requirePlayerId(playerId);

        if (walletRepository.existsByPlayerId(validatedPlayerId)) {
            throw WalletException.walletAlreadyExists(validatedPlayerId);
        }

        try {
            Wallet wallet = walletRepository.saveAndFlush(new Wallet(validatedPlayerId));
            log.info("Created wallet for playerId={}", validatedPlayerId);
            return toSnapshot(wallet);
        } catch (DataIntegrityViolationException exception) {
            // The database uniqueness constraint closes the race between two concurrent creates.
            throw WalletException.walletAlreadyExists(validatedPlayerId);
        }
    }

    @Transactional(readOnly = true)
    public WalletSnapshot getBalance(UUID playerId) {
        UUID validatedPlayerId = requirePlayerId(playerId);
        Wallet wallet = walletRepository.findByPlayerId(validatedPlayerId)
                .orElseThrow(() -> WalletException.walletNotFound(validatedPlayerId));
        return toSnapshot(wallet);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResult credit(UUID playerId, MoneyMovementCommand command) {
        return applyMovement(playerId, TransactionType.CREDIT, command);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResult debit(UUID playerId, MoneyMovementCommand command) {
        return applyMovement(playerId, TransactionType.DEBIT, command);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MoneyMovementResult refund(
            UUID playerId,
            UUID transactionId,
            RefundCommand command
    ) {
        UUID validatedPlayerId = requirePlayerId(playerId);
        UUID validatedTransactionId = requireTransactionId(transactionId);
        ValidatedRefund refund = validateRefund(command);

        // Refunds serialize with every other mutation for this wallet. Checking idempotency first
        // preserves replay semantics even after the original debit has already been refunded.
        Wallet wallet = walletRepository.findByPlayerIdForUpdate(validatedPlayerId)
                .orElseThrow(() -> WalletException.walletNotFound(validatedPlayerId));

        LedgerEntry existingEntry = ledgerEntryRepository.findByWalletIdAndIdempotencyKey(
                wallet.getId(), refund.idempotencyKey()
        ).orElse(null);
        if (existingEntry != null) {
            if (!existingEntry.representsRefund(
                    validatedTransactionId,
                    refund.reason(),
                    refund.referenceId()
            )) {
                throw WalletException.idempotencyConflict(
                        validatedPlayerId,
                        refund.idempotencyKey()
                );
            }
            return toResult(existingEntry, true);
        }

        LedgerEntry originalEntry = ledgerEntryRepository.findByIdAndWalletId(
                validatedTransactionId,
                wallet.getId()
        ).orElseThrow(() -> WalletException.transactionNotFound(
                validatedPlayerId,
                validatedTransactionId
        ));

        if (originalEntry.getType() != TransactionType.DEBIT) {
            throw WalletException.transactionNotRefundable(
                    validatedTransactionId,
                    originalEntry.getType()
            );
        }

        LedgerEntry priorRefund = ledgerEntryRepository.findRefundByTransactionId(
                validatedTransactionId
        ).orElse(null);
        if (priorRefund != null) {
            throw WalletException.transactionAlreadyRefunded(
                    validatedTransactionId,
                    priorRefund.getId()
            );
        }

        long balanceAfter = applyCredit(
                wallet,
                originalEntry.getAmount(),
                validatedPlayerId
        );
        LedgerEntry refundEntry = ledgerEntryRepository.save(LedgerEntry.refund(
                wallet,
                originalEntry.getAmount(),
                balanceAfter,
                refund.reason(),
                refund.referenceId(),
                refund.idempotencyKey(),
                validatedTransactionId
        ));

        log.info(
                "Refunded wallet transaction playerId={} transactionId={} refundTransactionId={} amount={} balanceAfter={}",
                validatedPlayerId,
                validatedTransactionId,
                refundEntry.getId(),
                refundEntry.getAmount(),
                balanceAfter
        );
        return toResult(refundEntry, false);
    }

    @Transactional(readOnly = true)
    public TransactionHistory getHistory(UUID playerId, int page, int size) {
        UUID validatedPlayerId = requirePlayerId(playerId);
        validatePagination(page, size);

        Wallet wallet = walletRepository.findByPlayerId(validatedPlayerId)
                .orElseThrow(() -> WalletException.walletNotFound(validatedPlayerId));
        Page<LedgerEntry> entries = ledgerEntryRepository.findHistory(
                wallet.getId(),
                PageRequest.of(page, size)
        );

        return new TransactionHistory(
                validatedPlayerId,
                entries.getContent().stream().map(WalletService::toView).toList(),
                entries.getNumber(),
                entries.getSize(),
                entries.getTotalElements(),
                entries.getTotalPages(),
                entries.isFirst(),
                entries.isLast()
        );
    }

    private MoneyMovementResult applyMovement(
            UUID playerId,
            TransactionType type,
            MoneyMovementCommand command
    ) {
        UUID validatedPlayerId = requirePlayerId(playerId);
        ValidatedMovement movement = validateMovement(command);

        // Every mutation for a wallet serializes on this row. The idempotency lookup happens
        // after the lock so a request waiting behind an identical request sees its committed row.
        Wallet wallet = walletRepository.findByPlayerIdForUpdate(validatedPlayerId)
                .orElseThrow(() -> WalletException.walletNotFound(validatedPlayerId));

        LedgerEntry existingEntry = ledgerEntryRepository.findByWalletIdAndIdempotencyKey(
                wallet.getId(), movement.idempotencyKey()
        ).orElse(null);

        if (existingEntry != null) {
            if (!existingEntry.represents(
                    type,
                    movement.amount(),
                    movement.reason(),
                    movement.referenceId()
            )) {
                throw WalletException.idempotencyConflict(
                        validatedPlayerId,
                        movement.idempotencyKey()
                );
            }
            log.debug(
                    "Replayed wallet transaction playerId={} transactionId={}",
                    validatedPlayerId,
                    existingEntry.getId()
            );
            return toResult(existingEntry, true);
        }

        long balanceAfter = switch (type) {
            case CREDIT -> applyCredit(wallet, movement.amount(), validatedPlayerId);
            case DEBIT -> applyDebit(wallet, movement.amount(), validatedPlayerId);
            case REFUND, TRANSFER_OUT, TRANSFER_IN, RESERVE, RELEASE ->
                    throw new IllegalArgumentException(
                            "This transaction type requires its dedicated operation"
                    );
        };

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(
                wallet,
                type,
                movement.amount(),
                balanceAfter,
                movement.reason(),
                movement.referenceId(),
                movement.idempotencyKey()
        ));

        log.info(
                "Applied wallet transaction playerId={} transactionId={} type={} amount={} balanceAfter={}",
                validatedPlayerId,
                entry.getId(),
                type,
                movement.amount(),
                balanceAfter
        );
        return toResult(entry, false);
    }

    private static long applyCredit(Wallet wallet, long amount, UUID playerId) {
        try {
            return wallet.credit(amount);
        } catch (ArithmeticException exception) {
            throw WalletException.balanceOverflow(playerId);
        }
    }

    private static long applyDebit(Wallet wallet, long amount, UUID playerId) {
        if (amount > wallet.getAvailableBalance()) {
            throw WalletException.insufficientFunds(
                    playerId,
                    amount,
                    wallet.getAvailableBalance()
            );
        }
        return wallet.debit(amount);
    }

    private static UUID requirePlayerId(UUID playerId) {
        if (playerId == null) {
            throw WalletException.invalidRequest("playerId is required");
        }
        return playerId;
    }

    private static UUID requireTransactionId(UUID transactionId) {
        if (transactionId == null) {
            throw WalletException.invalidRequest("transactionId is required");
        }
        return transactionId;
    }

    private static ValidatedMovement validateMovement(MoneyMovementCommand command) {
        if (command == null) {
            throw WalletException.invalidRequest("Money movement request is required");
        }
        if (command.amount() <= 0L) {
            throw WalletException.invalidAmount(command.amount());
        }

        return new ValidatedMovement(
                command.amount(),
                normalizeRequired(command.reason(), "reason", MAX_REASON_LENGTH),
                normalizeRequired(command.referenceId(), "referenceId", MAX_REFERENCE_LENGTH),
                normalizeIdempotencyKey(command.idempotencyKey())
        );
    }

    private static ValidatedRefund validateRefund(RefundCommand command) {
        if (command == null) {
            throw WalletException.invalidRequest("Refund request is required");
        }
        return new ValidatedRefund(
                normalizeRequired(command.reason(), "reason", MAX_REASON_LENGTH),
                normalizeRequired(command.referenceId(), "referenceId", MAX_REFERENCE_LENGTH),
                normalizeIdempotencyKey(command.idempotencyKey())
        );
    }

    private static String normalizeIdempotencyKey(String value) {
        String idempotencyKey = normalizeRequired(
                value,
                "Idempotency-Key",
                MAX_IDEMPOTENCY_KEY_LENGTH
        );
        if (!IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw WalletException.invalidRequest(
                    "Idempotency-Key must start with an alphanumeric character and contain only "
                            + "letters, numbers, '.', '_', ':' or '-' (maximum 100 characters)"
            );
        }
        return idempotencyKey;
    }

    private static String normalizeRequired(String value, String fieldName, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw WalletException.invalidRequest(fieldName + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw WalletException.invalidRequest(
                    fieldName + " must not exceed " + maximumLength + " characters"
            );
        }
        return normalized;
    }

    private static void validatePagination(int page, int size) {
        if (page < 0) {
            throw WalletException.invalidRequest("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw WalletException.invalidRequest("size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private static WalletSnapshot toSnapshot(Wallet wallet) {
        return new WalletSnapshot(
                wallet.getPlayerId(),
                wallet.getBalance(),
                wallet.getCreatedAt(),
                wallet.getUpdatedAt()
        );
    }

    private static MoneyMovementResult toResult(LedgerEntry entry, boolean replayed) {
        return new MoneyMovementResult(
                entry.getId(),
                entry.getWallet().getPlayerId(),
                entry.getType(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getReason(),
                entry.getReferenceId(),
                entry.getIdempotencyKey(),
                entry.getReversalOfEntryId(),
                entry.getCreatedAt(),
                replayed
        );
    }

    private static LedgerEntryView toView(LedgerEntry entry) {
        return new LedgerEntryView(
                entry.getId(),
                entry.getType(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getReason(),
                entry.getReferenceId(),
                entry.getReversalOfEntryId(),
                entry.getTransferId(),
                entry.getReservationId(),
                entry.getCreatedAt()
        );
    }

    private record ValidatedMovement(
            long amount,
            String reason,
            String referenceId,
            String idempotencyKey
    ) {
    }

    private record ValidatedRefund(
            String reason,
            String referenceId,
            String idempotencyKey
    ) {
    }
}
