-- V2: shipping_fee 컬럼을 settlement에서 sourcing으로 이동 및 logistics_cost로 이름 변경 (PostgreSQL)

ALTER TABLE sb_order_line_item ADD COLUMN IF NOT EXISTS logistics_cost DECIMAL(10,2);

UPDATE sb_order_line_item SET logistics_cost = shipping_fee WHERE shipping_fee IS NOT NULL;

ALTER TABLE sb_order_line_item DROP COLUMN IF EXISTS shipping_fee;
