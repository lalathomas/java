package io.github.lalathomas.walletledger.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Immutable
@Table(
        name = "ledger_entries",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_ledger_wallet_idempotency",
                        columnNames = {"wallet_id", "idempotency_key"}
                ),
                @UniqueConstraint(
                        name = "uk_ledger_refund_source",
                        columnNames = "reversal_of_entry_id"
                ),
                @UniqueConstraint(
                        name = "uk_ledger_transfer_direction",
                        columnNames = {"transfer_id", "transaction_type"}
                ),
                @UniqueConstraint(
                        name = "uk_ledger_reservation_action",
                        columnNames = {"reservation_id", "transaction_type"}
                )
        }
)
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, updatable = false, length = 20)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "balance_after", nullable = false, updatable = false)
    private long balanceAfter;

    @Column(name = "reason", nullable = false, updatable = false, length = 255)
    private String reason;

    @Column(name = "reference_id", nullable = false, updatable = false, length = 100)
    private String referenceId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "reversal_of_entry_id", updatable = false)
    private UUID reversalOfEntryId;

    @Column(name = "transfer_id", updatable = false)
    private UUID transferId;

    @Column(name = "counterparty_wallet_id", updatable = false)
    private Long counterpartyWalletId;

    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // Required by JPA.
    }

    public LedgerEntry(
            Wallet wallet,
            TransactionType type,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey
    ) {
        this(wallet, type, amount, balanceAfter, reason, referenceId, idempotencyKey,
                null, null, null, null);
    }

    private LedgerEntry(
            Wallet wallet,
            TransactionType type,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            UUID reversalOfEntryId,
            UUID transferId,
            Long counterpartyWalletId,
            UUID reservationId
    ) {
        this.wallet = Objects.requireNonNull(wallet, "wallet must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.reversalOfEntryId = reversalOfEntryId;
        this.transferId = transferId;
        this.counterpartyWalletId = counterpartyWalletId;
        this.reservationId = reservationId;
        validateShape();
    }

    public static LedgerEntry refund(
            Wallet wallet, long amount, long balanceAfter, String reason,
            String referenceId, String idempotencyKey, UUID reversalOfEntryId
    ) {
        return new LedgerEntry(wallet, TransactionType.REFUND, amount, balanceAfter,
                reason, referenceId, idempotencyKey,
                Objects.requireNonNull(reversalOfEntryId), null, null, null);
    }

    public static LedgerEntry transfer(
            Wallet wallet, TransactionType type, long amount, long balanceAfter,
            String reason, String referenceId, String idempotencyKey,
            UUID transferId, Long counterpartyWalletId
    ) {
        return new LedgerEntry(wallet, type, amount, balanceAfter, reason, referenceId,
                idempotencyKey, null, Objects.requireNonNull(transferId),
                Objects.requireNonNull(counterpartyWalletId), null);
    }

    public static LedgerEntry reservationAction(
            Wallet wallet, TransactionType type, long amount, long balanceAfter,
            String reason, String referenceId, String idempotencyKey, UUID reservationId
    ) {
        return new LedgerEntry(wallet, type, amount, balanceAfter, reason, referenceId,
                idempotencyKey, null, null, null, Objects.requireNonNull(reservationId));
    }

    private void validateShape() {
        if ((type == TransactionType.REFUND) != (reversalOfEntryId != null)) {
            throw new IllegalArgumentException("Only refunds identify a reversed transaction");
        }
        boolean transfer = type == TransactionType.TRANSFER_OUT
                || type == TransactionType.TRANSFER_IN;
        if (transfer != (transferId != null && counterpartyWalletId != null)) {
            throw new IllegalArgumentException("Transfer metadata must match the transaction type");
        }
        boolean reservationAction = type == TransactionType.RESERVE
                || type == TransactionType.RELEASE;
        if (reservationAction && reservationId == null) {
            throw new IllegalArgumentException("Reservation actions require a reservation ID");
        }
        if (reservationId != null && !reservationAction && type != TransactionType.DEBIT) {
            throw new IllegalArgumentException("Only reservation actions or captured debits use a reservation ID");
        }
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean represents(
            TransactionType requestedType, long requestedAmount,
            String requestedReason, String requestedReferenceId
    ) {
        return this.type == requestedType
                && this.amount == requestedAmount
                && this.reason.equals(requestedReason)
                && this.referenceId.equals(requestedReferenceId)
                && reversalOfEntryId == null
                && transferId == null
                && reservationId == null;
    }

    public boolean representsRefund(
            UUID requestedTransactionId, String requestedReason, String requestedReferenceId
    ) {
        return type == TransactionType.REFUND
                && Objects.equals(reversalOfEntryId, requestedTransactionId)
                && reason.equals(requestedReason)
                && referenceId.equals(requestedReferenceId);
    }

    public boolean representsTransfer(
            long requestedAmount, String requestedReason,
            String requestedReferenceId, Long requestedCounterpartyWalletId
    ) {
        return type == TransactionType.TRANSFER_OUT
                && amount == requestedAmount
                && reason.equals(requestedReason)
                && referenceId.equals(requestedReferenceId)
                && Objects.equals(counterpartyWalletId, requestedCounterpartyWalletId);
    }

    public boolean representsReservationAction(
            UUID requestedReservationId, TransactionType requestedType,
            long requestedAmount, String requestedReason, String requestedReferenceId
    ) {
        return type == requestedType
                && amount == requestedAmount
                && reason.equals(requestedReason)
                && referenceId.equals(requestedReferenceId)
                && Objects.equals(reservationId, requestedReservationId);
    }

    public UUID getId() { return id; }
    public Wallet getWallet() { return wallet; }
    public TransactionType getType() { return type; }
    public long getAmount() { return amount; }
    public long getBalanceAfter() { return balanceAfter; }
    public String getReason() { return reason; }
    public String getReferenceId() { return referenceId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getReversalOfEntryId() { return reversalOfEntryId; }
    public UUID getTransferId() { return transferId; }
    public Long getCounterpartyWalletId() { return counterpartyWalletId; }
    public UUID getReservationId() { return reservationId; }
    public Instant getCreatedAt() { return createdAt; }
}
