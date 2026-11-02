-- Shared by every service that publishes events; Flyway picks it up from the shared jar.
CREATE TABLE outbox (
    id          BIGSERIAL PRIMARY KEY,
    topic       TEXT        NOT NULL,
    message_key TEXT        NOT NULL,
    payload     TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
