CREATE TABLE payments (
    id               UUID PRIMARY KEY,
    idempotency_key  TEXT UNIQUE,
    customer_id      TEXT          NOT NULL,
    amount           NUMERIC(17,2) NOT NULL CHECK (amount > 0),
    currency         CHAR(3)       NOT NULL,
    status           TEXT          NOT NULL,
    exception_status TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at     TIMESTAMPTZ
);
