-- V3: is_unipass_done를 sb_order에서 sb_order_line_item으로 이동

-- 1. sb_order_line_item에 새 컬럼 추가
ALTER TABLE sb_order_line_item ADD COLUMN is_unipass_done BOOLEAN;

-- 2. sb_order의 is_unipass_done 데이터를 sb_order_line_item으로 이전
-- (동일한 주문의 모든 라인아이템에 복사)
UPDATE sb_order_line_item oli
JOIN sb_order o ON oli.order_id = o.id
SET oli.is_unipass_done = o.is_unipass_done
WHERE o.is_unipass_done IS NOT NULL;

-- 3. sb_order에서 기존 컬럼 제거
ALTER TABLE sb_order DROP COLUMN is_unipass_done;
