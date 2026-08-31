package io.github.lalathomas.walletledger.wallet.persistence;

import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {

    <S extends LedgerEntry> S save(S entry);

    @Query("""
            select entry
            from LedgerEntry entry
            where entry.wallet.id = :walletId
              and entry.idempotencyKey = :idempotencyKey
            """)
    Optional<LedgerEntry> findByWalletIdAndIdempotencyKey(
            @Param("walletId") Long walletId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Query("""
            select entry
            from LedgerEntry entry
            where entry.wallet.id = :walletId
              and entry.id = :transactionId
            """)
    Optional<LedgerEntry> findByWalletIdAndTransactionId(
            @Param("walletId") Long walletId,
            @Param("transactionId") UUID transactionId
    );

    @Query("""
            select entry
            from LedgerEntry entry
            where entry.wallet.id = :walletId
              and entry.refundedDebit.id = :debitId
            """)
    Optional<LedgerEntry> findRefundByWalletIdAndDebitId(
            @Param("walletId") Long walletId,
            @Param("debitId") UUID debitId
    );

    @Query(
            value = """
                    select entry
                    from LedgerEntry entry
                    where entry.wallet.id = :walletId
                    order by entry.createdAt desc, entry.id desc
                    """,
            countQuery = """
                    select count(entry)
                    from LedgerEntry entry
                    where entry.wallet.id = :walletId
                    """
    )
    Page<LedgerEntry> findHistory(@Param("walletId") Long walletId, Pageable pageable);
}
