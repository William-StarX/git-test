CREATE SCHEMA IF NOT EXISTS catalog;

CREATE TABLE IF NOT EXISTS catalog.menu_item (
    id BIGINT PRIMARY KEY,
    name TEXT NOT NULL,
    price_cents BIGINT NOT NULL
);

DO $$
BEGIN
    IF to_regclass('public.menu_item') IS NOT NULL THEN
        INSERT INTO catalog.menu_item (id, name, price_cents)
        SELECT id, name, price_cents
        FROM public.menu_item
        ON CONFLICT (id) DO NOTHING;
    END IF;
END $$;

INSERT INTO catalog.menu_item (id, name, price_cents) VALUES
    (1, '黄焖鸡米饭', 1290),
    (2, '宫保鸡丁套餐', 1590),
    (3, '桂花酸梅汤', 590)
ON CONFLICT (id) DO NOTHING;
