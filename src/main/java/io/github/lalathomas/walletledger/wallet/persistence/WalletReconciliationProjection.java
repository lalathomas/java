package io.github.lalathomas.walletledger.wallet.persistence;

import java.math.BigDecimal;

public interface WalletReconciliationProjection {

    long getStoredBalance();

    BigDecimal getCalculatedBalance();

    long getTransactionCount();
}
