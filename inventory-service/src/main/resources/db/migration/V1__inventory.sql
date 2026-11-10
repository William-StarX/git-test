CREATE SCHEMA IF NOT EXISTS inventory;

CREATE TABLE IF NOT EXISTS inventory.stock (
    menu_item_id BIGINT PRIMARY KEY,
    available INTEGER NOT NULL CHECK (available >= 0)
);

CREATE TABLE IF NOT EXISTS inventory.reservation (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    request_hash VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS inventory.reservation_line (
    reservation_id UUID NOT NULL REFERENCES inventory.reservation (id),
    menu_item_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    PRIMARY KEY (reservation_id, menu_item_id)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM inventory.stock) THEN
        INSERT INTO inventory.stock (menu_item_id, available) VALUES
            (1, 10),
            (2, 30),
            (3, 20);
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.menu_item') IS NOT NULL THEN
        INSERT INTO inventory.stock (menu_item_id, available)
        SELECT mi.id, COALESCE(mi.stock, 0)
        FROM public.menu_item mi
        WHERE mi.id IS NOT NULL
        ON CONFLICT (menu_item_id) DO UPDATE
            SET available = EXCLUDED.available;
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('public.takeout_order') IS NOT NULL
        AND to_regclass('public.order_line') IS NOT NULL THEN
        INSERT INTO inventory.reservation (id, status, request_hash)
        SELECT o.id, 'RESERVED', NULL
        FROM public.takeout_order o
        WHERE o.status <> 'CANCELLED'
        ON CONFLICT (id) DO NOTHING;

        INSERT INTO inventory.reservation_line (reservation_id, menu_item_id, quantity)
        SELECT ol.order_id, ol.menu_item_id, ol.quantity
        FROM public.order_line ol
        JOIN public.takeout_order o ON o.id = ol.order_id
        WHERE o.status <> 'CANCELLED'
        ON CONFLICT (reservation_id, menu_item_id) DO NOTHING;
    END IF;
END $$;
