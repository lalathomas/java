package io.github.lalathomas.walletledger.wallet.application;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Testcontainers(disabledWithoutDocker = true)
class PostgresV1ToV2MigrationIntegrationTest {

    private static final String MIGRATION_LOCATION = "classpath:db/migration";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17.6-alpine")
    );

    @Test
    void migratesPopulatedV1DataAndEnforcesV2RefundConstraints() throws Exception {
        Flyway v1Flyway = flywayAt(MigrationVersion.fromVersion("1"));
        v1Flyway.migrate();
        assertThat(v1Flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        UUID playerId = UUID.randomUUID();
        UUID creditId = UUID.randomUUID();
        UUID debitId = UUID.randomUUID();
        OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        long walletId;

        try (Connection connection = POSTGRES.createConnection("")) {
            walletId = insertWallet(connection, playerId, 70, createdAt);
            insertV1Ledger(
                    connection,
                    creditId,
                    walletId,
                    "CREDIT",
                    100,
                    100,
                    "Initial balance",
                    "initial-credit",
                    "v1-credit-key",
                    createdAt
            );
            insertV1Ledger(
                    connection,
                    debitId,
                    walletId,
                    "DEBIT",
                    30,
                    70,
                    "Existing purchase",
                    "existing-purchase",
                    "v1-debit-key",
                    createdAt.plusSeconds(1)
            );
        }

        Flyway latestFlyway = flywayAt(null);
        latestFlyway.migrate();
        assertThat(latestFlyway.info().current().getVersion().getVersion()).isEqualTo("2");

        try (Connection connection = POSTGRES.createConnection("")) {
            assertLegacyDataWasPreserved(connection, playerId, creditId, debitId);

            UUID refundId = UUID.randomUUID();
            applyValidRefund(
                    connection,
                    refundId,
                    walletId,
                    debitId,
                    createdAt.plusSeconds(2)
            );
            assertThat(queryLong(connection, "SELECT balance FROM wallets")).isEqualTo(100);
            assertThat(queryLong(connection, "SELECT COUNT(*) FROM ledger_entries")).isEqualTo(3);
            assertThat(queryUuid(
                    connection,
                    "SELECT refunded_debit_id FROM ledger_entries WHERE id = ?",
                    refundId
            )).isEqualTo(debitId);

            SQLException duplicateRefund = assertThrows(
                    SQLException.class,
                    () -> insertV2Ledger(
                            connection,
                            UUID.randomUUID(),
                            walletId,
                            "CREDIT",
                            30,
                            100,
                            "Duplicate refund",
                            "duplicate-refund",
                            "duplicate-refund-key",
                            createdAt.plusSeconds(3),
                            debitId
                    )
            );
            assertThat(duplicateRefund.getSQLState()).isEqualTo("23505");

            SQLException missingTarget = assertThrows(
                    SQLException.class,
                    () -> insertV2Ledger(
                            connection,
                            UUID.randomUUID(),
                            walletId,
                            "CREDIT",
                            30,
                            100,
                            "Missing target",
                            "missing-target",
                            "missing-target-key",
                            createdAt.plusSeconds(4),
                            UUID.randomUUID()
                    )
            );
            assertThat(missingTarget.getSQLState()).isEqualTo("23503");

            SQLException linkedDebit = assertThrows(
                    SQLException.class,
                    () -> insertV2Ledger(
                            connection,
                            UUID.randomUUID(),
                            walletId,
                            "DEBIT",
                            1,
                            99,
                            "Invalid linked debit",
                            "invalid-linked-debit",
                            "invalid-linked-debit-key",
                            createdAt.plusSeconds(5),
                            creditId
                    )
            );
            assertThat(linkedDebit.getSQLState()).isEqualTo("23514");
        }
    }

    private Flyway flywayAt(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword()
                )
                .locations(MIGRATION_LOCATION);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private long insertWallet(
            Connection connection,
            UUID playerId,
            long balance,
            OffsetDateTime timestamp
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO wallets (player_id, balance, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)) {
            statement.setObject(1, playerId);
            statement.setLong(2, balance);
            statement.setObject(3, timestamp);
            statement.setObject(4, timestamp);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getLong(1);
            }
        }
    }

    private void insertV1Ledger(
            Connection connection,
            UUID id,
            long walletId,
            String type,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            OffsetDateTime createdAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries (
                    id, wallet_id, transaction_type, amount, balance_after,
                    reason, reference_id, idempotency_key, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            setLedgerValues(
                    statement,
                    id,
                    walletId,
                    type,
                    amount,
                    balanceAfter,
                    reason,
                    referenceId,
                    idempotencyKey,
                    createdAt
            );
            statement.executeUpdate();
        }
    }

    private void insertV2Ledger(
            Connection connection,
            UUID id,
            long walletId,
            String type,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            OffsetDateTime createdAt,
            UUID refundedDebitId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ledger_entries (
                    id, wallet_id, transaction_type, amount, balance_after,
                    reason, reference_id, idempotency_key, created_at, refunded_debit_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            setLedgerValues(
                    statement,
                    id,
                    walletId,
                    type,
                    amount,
                    balanceAfter,
                    reason,
                    referenceId,
                    idempotencyKey,
                    createdAt
            );
            statement.setObject(10, refundedDebitId);
            statement.executeUpdate();
        }
    }

    private void setLedgerValues(
            PreparedStatement statement,
            UUID id,
            long walletId,
            String type,
            long amount,
            long balanceAfter,
            String reason,
            String referenceId,
            String idempotencyKey,
            OffsetDateTime createdAt
    ) throws SQLException {
        statement.setObject(1, id);
        statement.setLong(2, walletId);
        statement.setString(3, type);
        statement.setLong(4, amount);
        statement.setLong(5, balanceAfter);
        statement.setString(6, reason);
        statement.setString(7, referenceId);
        statement.setString(8, idempotencyKey);
        statement.setObject(9, createdAt);
    }

    private void assertLegacyDataWasPreserved(
            Connection connection,
            UUID playerId,
            UUID creditId,
            UUID debitId
    ) throws SQLException {
        assertThat(queryLong(connection, "SELECT COUNT(*) FROM wallets")).isEqualTo(1);
        assertThat(queryLong(connection, "SELECT balance FROM wallets")).isEqualTo(70);
        assertThat(queryUuid(
                connection,
                "SELECT player_id FROM wallets LIMIT 1",
                null
        )).isEqualTo(playerId);
        assertThat(queryLong(connection, "SELECT COUNT(*) FROM ledger_entries")).isEqualTo(2);
        assertThat(queryLong(
                connection,
                "SELECT COUNT(*) FROM ledger_entries WHERE refunded_debit_id IS NULL"
        )).isEqualTo(2);
        assertThat(queryString(
                connection,
                "SELECT transaction_type FROM ledger_entries WHERE id = ?",
                creditId
        )).isEqualTo("CREDIT");
        assertThat(queryString(
                connection,
                "SELECT transaction_type FROM ledger_entries WHERE id = ?",
                debitId
        )).isEqualTo("DEBIT");
    }

    private void applyValidRefund(
            Connection connection,
            UUID refundId,
            long walletId,
            UUID debitId,
            OffsetDateTime createdAt
    ) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE wallets
                    SET balance = 100, updated_at = ?
                    WHERE id = ?
                    """)) {
                statement.setObject(1, createdAt);
                statement.setLong(2, walletId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            insertV2Ledger(
                    connection,
                    refundId,
                    walletId,
                    "CREDIT",
                    30,
                    100,
                    "Refund existing purchase",
                    "refund-existing-purchase",
                    "v2-refund-key",
                    createdAt,
                    debitId
            );
            connection.commit();
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private long queryLong(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getLong(1);
        }
    }

    private UUID queryUuid(
            Connection connection,
            String sql,
            UUID parameter
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (parameter != null) {
                statement.setObject(1, parameter);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private String queryString(
            Connection connection,
            String sql,
            UUID parameter
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return resultSet.getString(1);
            }
        }
    }
}
