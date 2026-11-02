-- One row per paymentId (the correlation id); each side fills its own columns as it arrives.
CREATE TABLE reconciliation_records (
    payment_id       UUID PRIMARY KEY,
    state            TEXT        NOT NULL DEFAULT 'PENDING',
    exception_type   TEXT,
    our_outcome      TEXT,
    our_amount       NUMERIC(17,2),
    our_currency     CHAR(3),
    our_received_at  TIMESTAMPTZ,
    bank_amount      NUMERIC(17,2),
    bank_currency    CHAR(3),
    bank_settled_at  TIMESTAMPTZ,
    bank_received_at TIMESTAMPTZ
);

CREATE INDEX reconciliation_records_pending ON reconciliation_records (payment_id) WHERE state = 'PENDING';
