package io.github.lalathomas.walletledger.wallet.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "wallets")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "player_id", nullable = false, unique = true, updatable = false)
    private UUID playerId;

    @Column(name = "balance", nullable = false)
    private long balance;

    @Column(name = "reserved_balance", nullable = false)
    private long reservedBalance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Wallet() {
        // Required by JPA.
    }

    public Wallet(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId must not be null");
        this.balance = 0L;
        this.reservedBalance = 0L;
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

    public long credit(long amount) {
        requirePositive(amount);
        this.balance = Math.addExact(this.balance, amount);
        return this.balance;
    }

    public long debit(long amount) {
        requirePositive(amount);
        if (amount > getAvailableBalance()) {
            throw new IllegalStateException("A wallet cannot spend reserved funds");
        }
        this.balance = Math.subtractExact(this.balance, amount);
        return this.balance;
    }

    public void reserve(long amount) {
        requirePositive(amount);
        if (amount > getAvailableBalance()) {
            throw new IllegalStateException("A wallet cannot reserve unavailable funds");
        }
        this.reservedBalance = Math.addExact(this.reservedBalance, amount);
    }

    public long captureReservation(long amount) {
        requirePositive(amount);
        if (amount > this.reservedBalance || amount > this.balance) {
            throw new IllegalStateException("Reserved funds are inconsistent with the wallet");
        }
        this.reservedBalance = Math.subtractExact(this.reservedBalance, amount);
        this.balance = Math.subtractExact(this.balance, amount);
        return this.balance;
    }

    public void releaseReservation(long amount) {
        requirePositive(amount);
        if (amount > this.reservedBalance) {
            throw new IllegalStateException("Cannot release more than the reserved balance");
        }
        this.reservedBalance = Math.subtractExact(this.reservedBalance, amount);
    }

    private static void requirePositive(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
    }

    public Long getId() {
        return id;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public long getBalance() {
        return balance;
    }

    public long getReservedBalance() {
        return reservedBalance;
    }

    public long getAvailableBalance() {
        return balance - reservedBalance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
