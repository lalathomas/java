package io.github.lalathomas.walletledger.wallet.application;

import io.github.lalathomas.walletledger.wallet.domain.LedgerEntry;
import io.github.lalathomas.walletledger.wallet.persistence.LedgerEntryRepository;
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

    @MockitoBean
    private LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void cleanDatabaseAndMock() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM fund_reservations");
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
}
