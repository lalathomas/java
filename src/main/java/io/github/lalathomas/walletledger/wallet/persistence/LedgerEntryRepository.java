package io.github.lalathomas.walletledger.wallet.persistence;

import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import io.github.lalathomas.walletledger.wallet.domain.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {

    <S extends LedgerEntry> S save(S entry);

    @Query("""
            select entry from LedgerEntry entry
            where entry.wallet.id = :walletId and entry.idempotencyKey = :idempotencyKey
            """)
    Optional<LedgerEntry> findByWalletIdAndIdempotencyKey(
            @Param("walletId") Long walletId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Query("""
            select entry from LedgerEntry entry
            where entry.id = :transactionId and entry.wallet.id = :walletId
            """)
    Optional<LedgerEntry> findByIdAndWalletId(
            @Param("transactionId") UUID transactionId,
            @Param("walletId") Long walletId
    );

    @Query("select entry from LedgerEntry entry where entry.reversalOfEntryId = :transactionId")
    Optional<LedgerEntry> findRefundByTransactionId(@Param("transactionId") UUID transactionId);

    @Query("""
            select entry from LedgerEntry entry
            where entry.transferId = :transferId order by entry.type
            """)
    List<LedgerEntry> findByTransferId(@Param("transferId") UUID transferId);

    @Query("""
            select entry from LedgerEntry entry
            where entry.reservationId = :reservationId and entry.type = :type
            """)
    Optional<LedgerEntry> findByReservationIdAndType(
            @Param("reservationId") UUID reservationId,
            @Param("type") TransactionType type
    );

    @Query(
            value = """
                    select entry from LedgerEntry entry
                    where entry.wallet.id = :walletId
                    order by entry.createdAt desc, entry.id desc
                    """,
            countQuery = "select count(entry) from LedgerEntry entry where entry.wallet.id = :walletId"
    )
    Page<LedgerEntry> findHistory(@Param("walletId") Long walletId, Pageable pageable);

    @Query(value = """
            select
                cast(coalesce(sum(case
                    when transaction_type in ('CREDIT', 'REFUND', 'TRANSFER_IN') then amount
                    when transaction_type in ('DEBIT', 'TRANSFER_OUT') then -amount
                    else 0 end), 0) as decimal(38, 0)) as calculatedBalance,
                count(*) as transactionCount
            from ledger_entries where wallet_id = :walletId
            """, nativeQuery = true)
    LedgerBalanceProjection calculateLedgerBalance(@Param("walletId") Long walletId);
}
