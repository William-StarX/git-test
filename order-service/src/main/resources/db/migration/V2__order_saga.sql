-- Durable order intent and idempotent compensation support.
-- Legacy order data (takeout_order, order_line, outbox_event, processed_event) is preserved.

CREATE TABLE IF NOT EXISTS order_intent (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    customer_id VARCHAR(64) NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ,
    CONSTRAINT order_intent_customer_key_unique UNIQUE (customer_id, request_key)
);

CREATE INDEX IF NOT EXISTS order_intent_status_created_idx
    ON order_intent (status, created_at);

CREATE TABLE IF NOT EXISTS order_release_retry (
    order_id UUID PRIMARY KEY,
    attempts INT NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS order_release_retry_completed_idx
    ON order_release_retry (completed, created_at);

ALTER TABLE order_line DROP CONSTRAINT IF EXISTS order_line_menu_item_id_fkey;
