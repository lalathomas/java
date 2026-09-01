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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "fund_reservations")
public class FundReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private ReservationStatus status;

    @Column(name = "reason", nullable = false, updatable = false, length = 255)
    private String reason;

    @Column(name = "reference_id", nullable = false, updatable = false, length = 100)
    private String referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected FundReservation() {
        // Required by JPA.
    }

    public FundReservation(Wallet wallet, long amount, String reason, String referenceId) {
        this.wallet = Objects.requireNonNull(wallet, "wallet must not be null");
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        this.amount = amount;
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.referenceId = Objects.requireNonNull(referenceId, "referenceId must not be null");
        this.status = ReservationStatus.ACTIVE;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void capture() {
        completeAs(ReservationStatus.CAPTURED);
    }

    public void release() {
        completeAs(ReservationStatus.RELEASED);
    }

    private void completeAs(ReservationStatus completedStatus) {
        if (status != ReservationStatus.ACTIVE) {
            throw new IllegalStateException("Only an active reservation can be completed");
        }
        this.status = completedStatus;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public UUID getId() {
        return id;
    }

    public Wallet getWallet() {
        return wallet;
    }

    public long getAmount() {
        return amount;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getReferenceId() {
        return referenceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
