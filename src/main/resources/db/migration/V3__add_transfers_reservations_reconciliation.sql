ALTER TABLE wallets
    ADD COLUMN reserved_balance BIGINT NOT NULL DEFAULT 0;

ALTER TABLE wallets
    ADD CONSTRAINT ck_wallet_reserved_non_negative CHECK (reserved_balance >= 0);

ALTER TABLE wallets
    ADD CONSTRAINT ck_wallet_reserved_within_balance CHECK (reserved_balance <= balance);

CREATE TABLE fund_reservations (
    id UUID PRIMARY KEY,
    wallet_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    status VARCHAR(10) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    reference_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_reservation_wallet
        FOREIGN KEY (wallet_id) REFERENCES wallets (id) ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_reservation_status
        CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED')),
    CONSTRAINT ck_reservation_completion
        CHECK (
            (status = 'ACTIVE' AND completed_at IS NULL)
            OR
            (status IN ('CAPTURED', 'RELEASED') AND completed_at IS NOT NULL)
        ),
    CONSTRAINT ck_reservation_reason_not_blank CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT ck_reservation_reference_not_blank CHECK (CHAR_LENGTH(TRIM(reference_id)) > 0)
);

CREATE INDEX idx_reservation_wallet_status
    ON fund_reservations (wallet_id, status, created_at DESC);

ALTER TABLE ledger_entries
    ALTER COLUMN transaction_type TYPE VARCHAR(20);

ALTER TABLE ledger_entries
    ADD COLUMN transfer_id UUID;

ALTER TABLE ledger_entries
    ADD COLUMN counterparty_wallet_id BIGINT;

ALTER TABLE ledger_entries
    ADD COLUMN reservation_id UUID;

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_counterparty_wallet
        FOREIGN KEY (counterparty_wallet_id) REFERENCES wallets (id) ON DELETE RESTRICT;

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_reservation
        FOREIGN KEY (reservation_id) REFERENCES fund_reservations (id) ON DELETE RESTRICT;

ALTER TABLE ledger_entries
    DROP CONSTRAINT ck_ledger_transaction_type;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_transaction_type
        CHECK (transaction_type IN (
            'CREDIT', 'DEBIT', 'REFUND',
            'TRANSFER_OUT', 'TRANSFER_IN', 'RESERVE', 'RELEASE'
        ));

ALTER TABLE ledger_entries
    DROP CONSTRAINT ck_ledger_refund_source;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_refund_source
        CHECK (
            (transaction_type = 'REFUND' AND reversal_of_entry_id IS NOT NULL)
            OR
            (transaction_type <> 'REFUND' AND reversal_of_entry_id IS NULL)
        );

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_transfer_shape
        CHECK (
            (
                transaction_type IN ('TRANSFER_OUT', 'TRANSFER_IN')
                AND transfer_id IS NOT NULL
                AND counterparty_wallet_id IS NOT NULL
            )
            OR
            (
                transaction_type NOT IN ('TRANSFER_OUT', 'TRANSFER_IN')
                AND transfer_id IS NULL
                AND counterparty_wallet_id IS NULL
            )
        );

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_reservation_shape
        CHECK (
            (transaction_type IN ('RESERVE', 'RELEASE') AND reservation_id IS NOT NULL)
            OR
            (transaction_type = 'DEBIT')
            OR
            (transaction_type NOT IN ('RESERVE', 'RELEASE', 'DEBIT') AND reservation_id IS NULL)
        );

ALTER TABLE ledger_entries
    ADD CONSTRAINT uk_ledger_transfer_direction UNIQUE (transfer_id, transaction_type);

ALTER TABLE ledger_entries
    ADD CONSTRAINT uk_ledger_reservation_action UNIQUE (reservation_id, transaction_type);

CREATE INDEX idx_ledger_transfer_id ON ledger_entries (transfer_id);
CREATE INDEX idx_ledger_reservation_id ON ledger_entries (reservation_id);
