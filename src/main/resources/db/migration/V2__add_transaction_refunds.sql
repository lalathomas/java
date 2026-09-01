ALTER TABLE ledger_entries
    ADD COLUMN reversal_of_entry_id UUID;

ALTER TABLE ledger_entries
    DROP CONSTRAINT ck_ledger_transaction_type;

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_transaction_type
        CHECK (transaction_type IN ('CREDIT', 'DEBIT', 'REFUND'));

ALTER TABLE ledger_entries
    ADD CONSTRAINT fk_ledger_refund_source
        FOREIGN KEY (reversal_of_entry_id) REFERENCES ledger_entries (id) ON DELETE RESTRICT;

ALTER TABLE ledger_entries
    ADD CONSTRAINT uk_ledger_refund_source UNIQUE (reversal_of_entry_id);

ALTER TABLE ledger_entries
    ADD CONSTRAINT ck_ledger_refund_source
        CHECK (
            (transaction_type = 'REFUND' AND reversal_of_entry_id IS NOT NULL)
            OR
            (transaction_type IN ('CREDIT', 'DEBIT') AND reversal_of_entry_id IS NULL)
        );
