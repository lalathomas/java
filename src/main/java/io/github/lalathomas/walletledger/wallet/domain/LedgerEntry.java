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
    @Column(name = "transaction_type", nullable = false, updatable = false, length = 10)
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
        this(wallet, type, amount, balanceAfter, reason, referenceId, idempotencyKey, null);
    }

    private LedgerEntry(
            Wallet wallet,
            TransactionType type,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            UUID reversalOfEntryId
    ) {
        this.wallet = Objects.requireNonNull(wallet, "wallet must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.reversalOfEntryId = reversalOfEntryId;
        if ((type == TransactionType.REFUND) != (reversalOfEntryId != null)) {
            throw new IllegalArgumentException(
                    "Only refund entries must identify the transaction they reverse"
            );
        }
    }

    public static LedgerEntry refund(
            Wallet wallet,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            UUID reversalOfEntryId
    ) {
        return new LedgerEntry(
                wallet,
                TransactionType.REFUND,
                amount,
                balanceAfter,
                reason,
                referenceId,
                idempotencyKey,
                Objects.requireNonNull(reversalOfEntryId, "reversalOfEntryId must not be null")
        );
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public boolean represents(
            TransactionType requestedType,
            long requestedAmount,
            String requestedReason,
            String requestedReferenceId
    ) {
        return this.type == requestedType
                && this.amount == requestedAmount
                && this.reason.equals(requestedReason)
                && this.referenceId.equals(requestedReferenceId);
    }

    public boolean representsRefund(
            UUID requestedTransactionId,
            String requestedReason,
            String requestedReferenceId
    ) {
        return this.type == TransactionType.REFUND
                && Objects.equals(this.reversalOfEntryId, requestedTransactionId)
                && this.reason.equals(requestedReason)
                && this.referenceId.equals(requestedReferenceId);
    }

    public UUID getId() {
        return id;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public TransactionType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getReversalOfEntryId() {
        return reversalOfEntryId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
