package io.github.lalathomas.walletledger.wallet.persistence;

import io.github.lalathomas.walletledger.wallet.domain.Wallet;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends Repository<Wallet, Long> {

    <S extends Wallet> S saveAndFlush(S wallet);

    boolean existsByPlayerId(UUID playerId);

    Optional<Wallet> findByPlayerId(UUID playerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select wallet from Wallet wallet where wallet.playerId = :playerId")
    Optional<Wallet> findByPlayerIdForUpdate(@Param("playerId") UUID playerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select wallet from Wallet wallet
            where wallet.playerId in :playerIds
            order by wallet.id
            """)
    List<Wallet> findAllByPlayerIdForUpdate(
            @Param("playerIds") Collection<UUID> playerIds
    );
}
