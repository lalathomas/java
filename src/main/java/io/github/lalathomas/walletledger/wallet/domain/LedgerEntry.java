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
                        name = "uk_ledger_refunded_debit",
                        columnNames = "refunded_debit_id"
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refunded_debit_id", updatable = false)
    private LedgerEntry refundedDebit;

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
            LedgerEntry refundedDebit
    ) {
        this.wallet = Objects.requireNonNull(wallet, "wallet must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId must not be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.refundedDebit = refundedDebit;
    }

    public static LedgerEntry refund(
            Wallet wallet,
            LedgerEntry debit,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey
    ) {
        Objects.requireNonNull(debit, "debit must not be null");
        if (debit.type != TransactionType.DEBIT) {
            throw new IllegalArgumentException("Only a debit entry can be refunded");
        }
        if (!sameWallet(wallet, debit.wallet)) {
            throw new IllegalArgumentException("The refunded debit must belong to the same wallet");
        }
        return new LedgerEntry(
                wallet,
                TransactionType.CREDIT,
                debit.amount,
                balanceAfter,
                reason,
                referenceId,
                idempotencyKey,
                debit
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
        return this.refundedDebit == null
                && this.type == requestedType
                && this.amount == requestedAmount
                && this.reason.equals(requestedReason)
                && this.referenceId.equals(requestedReferenceId);
    }

    public boolean representsRefund(
            UUID requestedDebitId,
            String requestedReason,
            String requestedReferenceId
    ) {
        return this.type == TransactionType.CREDIT
                && this.refundedDebit != null
                && this.refundedDebit.getId().equals(requestedDebitId)
                && this.reason.equals(requestedReason)
                && this.referenceId.equals(requestedReferenceId);
    }

    private static boolean sameWallet(Wallet first, Wallet second) {
        Objects.requireNonNull(first, "wallet must not be null");
        Objects.requireNonNull(second, "debit wallet must not be null");
        if (first == second) {
            return true;
        }
        return first.getId() != null && first.getId().equals(second.getId());
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

    public UUID getRefundedDebitId() {
        return refundedDebit == null ? null : refundedDebit.getId();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
