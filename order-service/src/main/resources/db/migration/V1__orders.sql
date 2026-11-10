CREATE TABLE menu_item (
    id bigint PRIMARY KEY,
    name varchar(120) NOT NULL,
    price_cents bigint NOT NULL CHECK (price_cents BETWEEN 1 AND 100000000),
    stock integer NOT NULL CHECK (stock >= 0)
);

CREATE TABLE takeout_order (
    id uuid PRIMARY KEY,
    customer_id varchar(64) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    total_cents bigint NOT NULL CHECK (total_cents >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    confirmed_at timestamptz,
    cancelled_at timestamptz,
    UNIQUE (customer_id, request_key)
);

CREATE TABLE order_line (
    order_id uuid NOT NULL REFERENCES takeout_order(id),
    menu_item_id bigint NOT NULL REFERENCES menu_item(id),
    item_name varchar(120) NOT NULL,
    unit_price_cents bigint NOT NULL CHECK (unit_price_cents > 0),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    PRIMARY KEY (order_id, menu_item_id)
);

CREATE TABLE outbox_event (
    event_id uuid PRIMARY KEY,
    order_id uuid NOT NULL UNIQUE REFERENCES takeout_order(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz
);

CREATE INDEX outbox_unpublished ON outbox_event (created_at, event_id) WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id uuid PRIMARY KEY REFERENCES outbox_event(event_id),
    order_id uuid NOT NULL REFERENCES takeout_order(id),
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO menu_item (id, name, price_cents, stock) VALUES
    (1, '黄焖鸡米饭', 1290, 10),
    (2, '宫保鸡丁套餐', 1590, 30),
    (3, '桂花酸梅汤', 590, 20);
