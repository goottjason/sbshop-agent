-- D-366 (D-360 6-3 고정 결정 7·14): 공통 가격 정책의 마진 하한.
-- 수수료를 뺀 순이익이 min(판매가 × margin_floor_rate%, margin_floor_amount원) 아래로 내려가지 않게 판매가를 올린다.
-- 둘 다 NULL이면 하한을 적용하지 않는다 — 적용 직후·배포 직후 가격은 바뀌지 않는다.
-- 소유자: sbshop (sb_price_policy). 추가형·nullable·멱등. 배포보다 먼저 적용한다(운영 ddl-auto=validate).
-- 적용: docker exec -i projects-postgres-1 psql -U sbshop -d sbshop -v ON_ERROR_STOP=1 -1 -f - < 이 파일

ALTER TABLE sb_price_policy ADD COLUMN IF NOT EXISTS margin_floor_rate NUMERIC(5,2);
ALTER TABLE sb_price_policy ADD COLUMN IF NOT EXISTS margin_floor_amount NUMERIC(15,2);
