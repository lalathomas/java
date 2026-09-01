package io.github.lalathomas.walletledger.wallet.persistence;

import io.github.lalathomas.walletledger.wallet.domain.FundReservation;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface FundReservationRepository extends Repository<FundReservation, UUID> {

    <S extends FundReservation> S save(S reservation);

    @Query("""
            select reservation
            from FundReservation reservation
            where reservation.id = :reservationId
              and reservation.wallet.id = :walletId
            """)
    Optional<FundReservation> findByIdAndWalletId(
            @Param("reservationId") UUID reservationId,
            @Param("walletId") Long walletId
    );

    @Query(value = """
            select
                cast(coalesce(sum(amount), 0) as decimal(38, 0)) as calculatedReservedBalance,
                count(*) as activeReservationCount
            from fund_reservations
            where wallet_id = :walletId and status = 'ACTIVE'
            """, nativeQuery = true)
    ReservationBalanceProjection calculateActiveReservations(@Param("walletId") Long walletId);
}
