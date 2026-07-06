-- V3: is_unipass_done를 sb_order에서 sb_order_line_item으로 이동 (PostgreSQL)

ALTER TABLE sb_order_line_item ADD COLUMN IF NOT EXISTS is_unipass_done BOOLEAN;

UPDATE sb_order_line_item oli
SET is_unipass_done = o.is_unipass_done
FROM sb_order o
WHERE oli.order_id = o.id AND o.is_unipass_done IS NOT NULL;

ALTER TABLE sb_order DROP COLUMN IF EXISTS is_unipass_done;
