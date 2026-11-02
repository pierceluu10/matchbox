CREATE TABLE exceptions (
    id                 UUID PRIMARY KEY,
    payment_id         UUID          NOT NULL,
    type               TEXT          NOT NULL,
    status             TEXT          NOT NULL,
    expected_amount    NUMERIC(17,2),
    settled_amount     NUMERIC(17,2),
    recommended_action TEXT          NOT NULL,
    resolution_note    TEXT,
    detected_at        TIMESTAMPTZ   NOT NULL,
    resolved_at        TIMESTAMPTZ,
    -- A payment has at most one exception of each type, which makes replayed events no-ops.
    UNIQUE (payment_id, type)
);

CREATE INDEX exceptions_status_detected ON exceptions (status, detected_at DESC);
