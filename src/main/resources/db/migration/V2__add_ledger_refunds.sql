ALTER TABLE ledger_entries
    ADD COLUMN refunded_debit_id UUID;

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_refunded_debit
        FOREIGN KEY (refunded_debit_id)
        REFERENCES ledger_entries (id)
        ON DELETE RESTRICT;

ALTER TABLE ledger_entries
    ADD CONSTRAINT uk_ledger_refunded_debit
        UNIQUE (refunded_debit_id);

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_refund_is_credit
        CHECK (refunded_debit_id IS NULL OR transaction_type = 'CREDIT');
