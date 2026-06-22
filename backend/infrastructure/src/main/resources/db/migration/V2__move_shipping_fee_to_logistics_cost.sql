-- V2: shipping_fee 컬럼을 settlement에서 sourcing으로 이동 및 logistics_cost로 이름 변경

-- 1. 새 컬럼 추가
ALTER TABLE sb_order_line_item ADD COLUMN logistics_cost DECIMAL(10,2);

-- 2. 기존 데이터 이전 (shipping_fee → logistics_cost)
UPDATE sb_order_line_item SET logistics_cost = shipping_fee WHERE shipping_fee IS NOT NULL;

-- 3. 기존 컬럼 제거
ALTER TABLE sb_order_line_item DROP COLUMN shipping_fee;
