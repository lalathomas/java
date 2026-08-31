package io.github.lalathomas.walletledger.wallet.persistence;

import io.github.lalathomas.walletledger.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends Repository<Wallet, Long> {

    <S extends Wallet> S saveAndFlush(S wallet);

    boolean existsByPlayerId(UUID playerId);

    Optional<Wallet> findByPlayerId(UUID playerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from Wallet wallet where wallet.playerId = :playerId")
    Optional<Wallet> findByPlayerIdForUpdate(@Param("playerId") UUID playerId);

    @Query(value = """
            select w.balance as "storedBalance",
                   coalesce(
                       sum(
                           case
                               when le.transaction_type = 'CREDIT'
                                   then cast(le.amount as numeric(38, 0))
                               else -cast(le.amount as numeric(38, 0))
                           end
                       ),
                       cast(0 as numeric(38, 0))
                   ) as "calculatedBalance",
                   count(le.id) as "transactionCount"
            from wallets w
            left join ledger_entries le on le.wallet_id = w.id
            where w.player_id = :playerId
            group by w.id, w.balance
            """, nativeQuery = true)
    Optional<WalletReconciliationProjection> reconcileByPlayerId(
            @Param("playerId") UUID playerId
    );
}
