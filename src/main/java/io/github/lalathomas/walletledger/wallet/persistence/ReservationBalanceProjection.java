package io.github.lalathomas.walletledger.wallet.persistence;

import java.math.BigDecimal;

public interface ReservationBalanceProjection {
    BigDecimal getCalculatedReservedBalance();

    long getActiveReservationCount();
}
