-- Q19: 상품 무게 표준은 kg. 0.01g = 0.00001kg를 보존한다.
-- 검토용 PostgreSQL DDL. 운영 적용하지 않았으며 새 코드 배포 전에 적용/검증한다.
-- 기존 numeric(10,2)의 정수 8자리는 그대로 유지한다. 기존 값의 g/kg 변환은 수행하지 않는다.
-- 롤백 시 소수 2자리로 축소하면 새 데이터가 손실되므로 이 확장 열을 유지한다.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
DO $weight_precision$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'sb_product' AND column_name = 'weight'
          AND data_type = 'numeric'
          AND ((numeric_precision = 10 AND numeric_scale = 2)
            OR (numeric_precision = 13 AND numeric_scale = 5))
    ) THEN
        RAISE EXCEPTION 'Unexpected sb_product.weight definition; inspect the schema before changing precision';
    END IF;
END;
$weight_precision$;
ALTER TABLE sb_product ALTER COLUMN weight TYPE numeric(13,5);
COMMIT;
