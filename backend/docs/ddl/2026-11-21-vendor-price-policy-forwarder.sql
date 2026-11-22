-- D-366 (D-360 6-3 고정 결정 3·4·14): 소싱처 가격 정책의 배대지(배송대행지) 요금표, 원화.
-- 배대지 배송비 = 기본 금액 + 단계 금액 × ⌈max(0, 계산 무게 − 기본 무게) ÷ 단계 무게⌉,
-- 계산 무게(g) = 상품 무게(kg) × 1000 × 묶음수량 + 포장 가산(g). 판매가 계산 때 총 매입가에 더한다(원가에 넣지 않는다).
-- forwarder_base_amount가 NULL이거나 0이면 배대지 배송비는 0이다 — 적용 직후·배포 직후 가격은 바뀌지 않는다.
-- 기존 ship_* 칸(해외 배송비, 수집 때 원가에 더함)과는 별개다.
-- 소유자: goottjason (sb_vendor_price_policy). 추가형·nullable·멱등. 배포보다 먼저 적용한다(운영 ddl-auto=validate).
-- 적용: docker exec -i projects-postgres-1 psql -U goottjason -d sbshop -v ON_ERROR_STOP=1 -1 -f - < 이 파일

ALTER TABLE sb_vendor_price_policy ADD COLUMN IF NOT EXISTS forwarder_base_amount NUMERIC(10,2);
ALTER TABLE sb_vendor_price_policy ADD COLUMN IF NOT EXISTS forwarder_base_weight_g INTEGER;
ALTER TABLE sb_vendor_price_policy ADD COLUMN IF NOT EXISTS forwarder_step_amount NUMERIC(10,2);
ALTER TABLE sb_vendor_price_policy ADD COLUMN IF NOT EXISTS forwarder_step_weight_g INTEGER;
ALTER TABLE sb_vendor_price_policy ADD COLUMN IF NOT EXISTS forwarder_packaging_g INTEGER;
