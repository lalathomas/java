package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerEntryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class WalletTransactionRollbackIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @MockitoBean
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanDatabaseAndMock() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM wallets");
        reset(ledgerEntryRepository);
    }

    @Test
    void ledgerFailureAfterBalanceMutationRollsBackTheWalletUpdate() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        when(ledgerEntryRepository.findByWalletIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("Forced ledger write failure"))
                .when(ledgerEntryRepository)
                .save(any(LedgerEntry.class));

        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> walletService.credit(
                        playerId,
                        new MoneyMovementCommand(
                                50,
                                "Rollback test",
                                "rollback-test-1",
                                "rollback-after-mutation"
                        )
                ));

        verify(ledgerEntryRepository).save(any(LedgerEntry.class));
        assertThat(walletService.getBalance(playerId).balance()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries",
                Long.class
        )).isZero();
    }

    @Test
    void refundLedgerFailureRollsBackWalletAndDoesNotConsumeTheKey() {
        UUID playerId = walletService.createWallet(UUID.randomUUID()).playerId();
        Long walletId = jdbcTemplate.queryForObject(
                "SELECT id FROM wallets WHERE player_id = ?",
                Long.class,
                playerId
        );
        UUID debitId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ledger_entries (
                    id, wallet_id, transaction_type, amount, balance_after,
                    reason, reference_id, idempotency_key, created_at
                ) VALUES (?, ?, 'DEBIT', 50, 0, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                debitId,
                walletId,
                "Original debit",
                "rollback-refund-debit",
                "rollback-original-debit"
        );

        EntityManager entityManager = entityManagerFactory.createEntityManager();
        LedgerEntry debit;
        try {
            debit = entityManager.find(LedgerEntry.class, debitId);
            debit.getWallet().getId();
        } finally {
            entityManager.close();
        }

        when(ledgerEntryRepository.findByWalletIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(ledgerEntryRepository.findByWalletIdAndTransactionId(walletId, debitId))
                .thenReturn(Optional.of(debit));
        when(ledgerEntryRepository.findRefundByWalletIdAndDebitId(walletId, debitId))
                .thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("Forced refund ledger write failure"))
                .when(ledgerEntryRepository)
                .save(any(LedgerEntry.class));

        RefundCommand refund = new RefundCommand(
                "Rollback refund",
                "rollback-refund-1",
                "rollback-refund-key"
        );
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> walletService.refund(playerId, debitId, refund));

        verify(ledgerEntryRepository).save(any(LedgerEntry.class));
        assertThat(walletService.getBalance(playerId).balance()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries",
                Long.class
        )).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE refunded_debit_id IS NOT NULL",
                Long.class
        )).isZero();

        // The failed key was never persisted; a retry would pass the idempotency lookup.
        verify(ledgerEntryRepository).findByWalletIdAndIdempotencyKey(
                walletId,
                "rollback-refund-key"
        );
    }
}
