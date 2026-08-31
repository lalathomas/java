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
}
